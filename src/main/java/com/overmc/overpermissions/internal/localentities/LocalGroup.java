package com.overmc.overpermissions.internal.localentities;

import java.util.*;
import java.util.concurrent.*;

import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import com.overmc.overpermissions.api.*;
import com.overmc.overpermissions.internal.TemporaryPermissionManager;
import com.overmc.overpermissions.internal.datasources.GroupDataSource;

/**
 * A class that represents a ram stored version of a database group.<br>
 * By definition, PermissionGroups should be treated like Bukkit's Player objects, and only names should be stored.
 */
public class LocalGroup extends LocalPermissionEntity implements PermissionGroup {
    private final GroupDataSource groupDataSource;
    private final TemporaryPermissionManager tempManager;

    private final String name;
    private final UUID uniqueId;
    private final Set<PermissionGroup> parents = new CopyOnWriteArraySet<>(); // These are fast for iteration, but fairly slow for modification.
    private final Set<PermissionGroup> children = new CopyOnWriteArraySet<>();

    // World specific data
    private final ConcurrentMap<String, LocalGroupWorldData> worldDataMap = new ConcurrentHashMap<>();

    // Users in this group, they are updated if things change.
    private final Set<LocalUser> usersInGroup = Collections.synchronizedSet(new HashSet<LocalUser>(32));

    private int priority;
    private final Object priorityLock = new Object();

    public LocalGroup(GroupDataSource groupSource, TemporaryPermissionManager tempManager, String name, int priority, boolean wildcardSupport) {
        super(groupSource, wildcardSupport);
        Preconditions.checkNotNull(groupSource, "groupSource");
        Preconditions.checkNotNull(tempManager, "tempManager");
        Preconditions.checkNotNull(name, "name");
        Preconditions.checkArgument(priority >= 0, "Priority must be greater than or equal to 0.");
        this.groupDataSource = groupSource;
        this.tempManager = tempManager;
        this.name = name;
        this.uniqueId = UUID.nameUUIDFromBytes(("LocalGroup:" + name).getBytes(Charsets.UTF_8));
        this.priority = priority;
    }

    // Utility method(s)
    protected LocalGroupWorldData getWorldData(String worldName) {
        Preconditions.checkNotNull(worldName, "worldName");
        worldName = worldName.toLowerCase();
        if (worldDataMap.containsKey(worldName)) {
            return worldDataMap.get(worldName);
        }
        return null;
    }

    private LocalGroupWorldData getOrCreateWorld(String worldName) {
        Preconditions.checkNotNull(worldName, "worldName");
        worldName = worldName.toLowerCase();
        LocalGroupWorldData world = worldDataMap.get(worldName);
        if (world == null) {
            world = new LocalGroupWorldData(this, worldName, tempManager, groupDataSource.createWorldDataSource(worldName));
            world.reloadMetadata();
            world.reloadPermissions();
            world.recalculatePermissions();
            worldDataMap.put(worldName, world);
        }
        return world;
    }

    public void reloadWorldPermissions( ) {
        for (LocalGroupWorldData world : worldDataMap.values()) {
            world.reloadPermissions();
            world.recalculatePermissions();
        }
    }

    public void reloadWorldMetadata( ) {
        for (LocalGroupWorldData world : worldDataMap.values()) {
            world.reloadMetadata();
        }
    }

    public void reloadParentsAndChildren(GroupManager groupManager) {
        Collection<String> newParentNames = groupDataSource.getParents();
        Collection<PermissionGroup> newParents = new ArrayList<PermissionGroup>(newParentNames.size());
        for (String name : newParentNames) {
            PermissionGroup g = groupManager.getGroup(name);
            if (g == null) {
                throw new RuntimeException("There was an invalid group parent set for a group! (group=" + this.name + ", parent=" + name + "), groups: (" + groupManager.getGroups() + ")");
            }
            newParents.add(g);
        }
        Collection<String> newChildrenNames = groupDataSource.getChildren();
        Collection<PermissionGroup> newChildren = new ArrayList<PermissionGroup>(newChildrenNames.size());
        for (String name : newChildrenNames) {
            PermissionGroup g = groupManager.getGroup(name);
            if (g == null) {
                throw new RuntimeException("There was an invalid group child set for a group! (group=" + this.name + ", child=" + name + "), groups: (" + groupManager.getGroups() + ")");
            }
            newChildren.add(g);
        }
        parents.clear();
        parents.addAll(newParents);
        children.clear();
        children.addAll(newChildren);
    }

    public void recalculatePlayerGroupData( ) {
        for (LocalUser user : usersInGroup) {
            user.recalculateParentData();
        }
        for (PermissionGroup child : children) {
            if (child instanceof LocalGroup) {
                ((LocalGroup) child).recalculatePlayerGroupData();
            } else {
                Bukkit.getLogger().warning("Group " + child.getName() + " (" + child.getClass().getName() + ")'s type isn't a local group, it's attributes can't be reloaded.");
            }
        }
    }

    @Override
    public boolean hasGlobalPermission(String permission) {
        Preconditions.checkNotNull(permission, "permission");
        return hasInternalPermission(permission);
    }

    @Override
    public boolean hasPermission(String permission, String worldName) {
        Preconditions.checkNotNull(permission, "permission");
        Preconditions.checkNotNull(worldName, "world name");
        LocalGroupWorldData w = getWorldData(worldName);
        if (w != null) {
            return w.hasInternalPermission(permission);
        }
        return false;
    }

    @Override
    public boolean hasGlobalPermissionNode(String permissionNode) {
        Preconditions.checkNotNull(permissionNode, "permission node");
        return hasInternalPermissionNode(permissionNode);
    }

    @Override
    public boolean hasPermissionNode(String permissionNode, String worldName) {
        Preconditions.checkNotNull(permissionNode, "permission node");
        Preconditions.checkNotNull(worldName, "world name");
        LocalGroupWorldData w = getWorldData(worldName);
        if (w == null) {
            return false;
        }
        return w.hasInternalPermissionNode(permissionNode);
    }

    @Override
    public TemporaryNodeBatch getTempPermissionNodes( ) {
        TemporaryNodeBatch.Builder builder = TemporaryNodeBatch.builder();
        for (TemporaryPermissionEntry e : getInternalTempPermissionEntries()) {
            builder.addGlobalNode(e.getNode(), e.getExpirationTime(), TimeUnit.MILLISECONDS);
        }
        for (Map.Entry<String, LocalGroupWorldData> entry : worldDataMap.entrySet()) {
            for (TemporaryPermissionEntry e : entry.getValue().getInternalTempPermissionEntries()) {
                builder.addNode(e.getNode(), entry.getKey(), e.getExpirationTime(), TimeUnit.MILLISECONDS);
            }
        }
        return builder.build();
    }

    @Override
    public boolean getGlobalPermission(String permission) {
        Preconditions.checkNotNull(permission, "permission");
        return getInternalPermission(permission);
    }

    @Override
    public boolean getPermission(String permission, String worldName) {
        Preconditions.checkNotNull(permission, "permission");
        Preconditions.checkNotNull(worldName, "world name");
        LocalGroupWorldData w = getWorldData(worldName);
        if (w == null) {
            return false;
        }
        return w.getInternalPermission(permission);
    }

    @Override
    public boolean hasGlobalPermission(Permission permission) {
        Preconditions.checkNotNull(permission);
        return hasGlobalPermission(permission.getName());
    }

    @Override
    public boolean hasPermission(Permission permission, String worldName) {
        Preconditions.checkNotNull(permission);
        return hasPermission(permission.getName(), worldName);
    }

    @Override
    public boolean getGlobalPermission(Permission permission) {
        Preconditions.checkNotNull(permission);
        return getGlobalPermission(permission.getName());
    }

    @Override
    public boolean getPermission(Permission permission, String worldName) {
        Preconditions.checkNotNull(permission);
        return getPermission(permission.getName(), worldName);
    }

    @Override
    public boolean addGlobalPermissionNode(String permissionNode) {
        Preconditions.checkNotNull(permissionNode, "permission node");
        return addInternalPermissionNode(permissionNode);
    }

    @Override
    public boolean addPermissionNode(String permissionNode, String worldName) {
        Preconditions.checkNotNull(permissionNode, "permission node");
        Preconditions.checkNotNull(worldName, "world name");
        return getOrCreateWorld(worldName).addInternalPermissionNode(permissionNode);
    }

    @Override
    public boolean addBatchPermissions(NodeBatch batch) {
        Preconditions.checkNotNull(batch, "batch");
        boolean changed = false;
        super.addInternalPermissionNodes(batch.getGlobalNodes());
        if (changed) {
            recalculatePermissions(batch.getGlobalNodes());
        }
        return changed;
    }

    @Override
    public boolean removeGlobalPermissionNode(String permissionNode) {
        Preconditions.checkNotNull(permissionNode, "permissionNode");
        if (super.removeInternalPermissionNode(permissionNode)) {
            recalculatePermission(permissionNode);
            return true;
        }
        return false;
    }

    @Override
    public boolean removePermissionNode(String permissionNode, String worldName) {
        Preconditions.checkNotNull(permissionNode, "permissionNode");
        Preconditions.checkNotNull(worldName, "worldName");
        worldName = worldName.toLowerCase();
        if (!worldDataMap.containsKey(worldName)) {
            return false;
        }
        return worldDataMap.get(worldName).removeInternalPermissionNode(permissionNode);
    }

    @Override
    public boolean removeBatchPermissions(NodeBatch batch) {
        Preconditions.checkNotNull(batch, "nodes");
        boolean changed = super.removeInternalPermissionNodes(batch.getGlobalNodes());
        for (String world : batch.getWorldNodes().keySet()) {
            if (worldDataMap.containsKey(world)) {
                if (worldDataMap.get(world).removeInternalPermissionNodes(batch.getWorldNodes().get(world))) {
                    changed = true;
                }
            }
        }
        return changed;
    }

    @Override
    public NodeBatch getPermissionNodes( ) {
        NodeBatch.Builder builder = NodeBatch.builder();
        for (String node : getInternalPermissionNodes()) {
            builder.addGlobalNode(node);
        }
        for (Map.Entry<String, LocalGroupWorldData> entry : worldDataMap.entrySet()) {
            for (String node : entry.getValue().getInternalPermissionNodes()) {
                builder.addNode(node, entry.getKey());
            }
        }
        return builder.build();
    }

    @Override
    public Map<String, Boolean> getPermissionValues(String worldName) {
        LocalGroupWorldData world = getWorldData(worldName);
        if (world == null) {
            return Collections.emptyMap();
        }
        return world.getInternalPermissionValues();
    }

    @Override
    public Map<String, Boolean> getGlobalPermissionValues( ) {
        return getInternalPermissionValues();
    }

    @Override
    public boolean hasGlobalTempPermissionNode(String permission) {
        Preconditions.checkNotNull(permission, "permission");
        return hasInternalTempPermissionNode(permission);
    }

    @Override
    public boolean hasTempPermissionNode(String permission, String worldName) {
        Preconditions.checkNotNull(permission, "permission");
        Preconditions.checkNotNull(worldName, "world name");
        LocalGroupWorldData w = getWorldData(worldName);
        if (w != null) {
            return worldDataMap.get(worldName).hasInternalTempPermissionNode(permission);
        }
        return false;
    }

    @Override
    public boolean addGlobalTempPermissionNode(String permissionNode, long time, TimeUnit unit) {
        Preconditions.checkNotNull(permissionNode, "permissionNode");
        Preconditions.checkNotNull(unit, "unit");
        Preconditions.checkArgument(time > 0, "time <= 0");
        return addInternalTempPermissionNode(permissionNode, time, unit);
    }

    @Override
    public boolean addTempPermissionNode(String permissionNode, String worldName, long time, TimeUnit unit) {
        Preconditions.checkNotNull(permissionNode, "permissionNode");
        Preconditions.checkNotNull(worldName, "worldName");
        Preconditions.checkNotNull(unit, "unit");
        Preconditions.checkArgument(time > 0, "time <= 0");
        worldName = worldName.toLowerCase();
        if (!worldDataMap.containsKey(worldName)) {
            return false;
        }
        return worldDataMap.get(worldName).addInternalTempPermissionNode(permissionNode, time, unit);
    }

    @Override
    public boolean addBatchTempPermissionNodes(TemporaryNodeBatch batch) {
        Preconditions.checkNotNull(batch, "nodes");
        boolean changed = super.addInternalTempPermissionNodes(batch.getGlobalNodes());
        for (String world : batch.getWorldNodes().keySet()) {
            if (worldDataMap.containsKey(world)) {
                if (worldDataMap.get(world).addInternalTempPermissionNodes(batch.getWorldNodes().get(world))) {
                    changed = true;
                }
            }
        }
        return changed;
    }

    @Override
    public boolean removeGlobalTempPermissionNode(String permissionNode) {
        Preconditions.checkNotNull(permissionNode, "permission node");
        return removeInternalTempPermissionNode(permissionNode);
    }

    @Override
    public boolean removeTempPermissionNode(String permissionNode, String worldName) {
        Preconditions.checkNotNull(permissionNode, "permission node");
        Preconditions.checkNotNull(worldName, "world name");
        if (!worldDataMap.containsKey(worldName)) {
            return false;
        }
        return worldDataMap.get(worldName).removeInternalTempPermissionNode(permissionNode);
    }

    @Override
    public boolean removeBatchTempPermissionNodes(TemporaryNodeBatch batch) {
        Preconditions.checkNotNull(batch, "nodes");
        boolean changed = super.removeInternalTempPermissionNodes(batch.getGlobalNodes());
        for (String world : batch.getWorldNodes().keySet()) {
            if (worldDataMap.containsKey(world)) {
                if (worldDataMap.get(world).removeInternalTempPermissionNodes(batch.getWorldNodes().get(world))) {
                    changed = true;
                }
            }
        }
        return changed;
    }

    @Override
    public boolean hasGlobalMeta(String key) {
        Preconditions.checkNotNull(key, "key");
        return hasInternalMeta(key);
    }

    @Override
    public boolean hasMeta(String key, String worldName) {
        Preconditions.checkNotNull(key, "key");
        Preconditions.checkNotNull(worldName, "world name");
        LocalGroupWorldData world = getWorldData(worldName);
        if (world == null) {
            return false;
        }
        return world.hasInternalMeta(key);
    }

    @Override
    public String getGlobalMeta(String key) {
        Preconditions.checkNotNull(key, "key");
        return getInternalMeta(key);
    }

    @Override
    public String getMeta(String key, String worldName) {
        Preconditions.checkNotNull(key, "key");
        Preconditions.checkNotNull(worldName, "world name");
        LocalGroupWorldData world = getWorldData(worldName);
        if (world == null) {
            return null;
        }
        return world.getInternalMeta(key);
    }

    @Override
    public void setGlobalMeta(String key, String value) {
        Preconditions.checkNotNull(key, "key");
        Preconditions.checkNotNull(value, "value");
        setInternalMeta(key, value);
    }

    @Override
    public void setMeta(String key, String value, String worldName) {
        Preconditions.checkNotNull(key, "key");
        Preconditions.checkNotNull(value, "value");
        Preconditions.checkNotNull(worldName, "world name");
        getOrCreateWorld(worldName).setInternalMeta(key, value);
    }

    @Override
    public boolean removeGlobalMeta(String key) {
        Preconditions.checkNotNull(key, "key");
        return removeInternalMeta(key);
    }

    @Override
    public boolean removeMeta(String key, String worldName) {
        Preconditions.checkNotNull(key, "key");
        Preconditions.checkNotNull(worldName, "world name");
        LocalGroupWorldData world = getWorldData(worldName);
        if (world == null) {
            return false;
        }
        return world.removeInternalMeta(key);
    }

    @Override
    public void setBatchMeta(MetadataBatch batch) {
        Preconditions.checkNotNull(batch, "nodes");
        setInternalMetaEntries(batch.getGlobalNodes());
        for (String world : batch.getWorldNodes().keySet()) {
            getOrCreateWorld(world).addInternalMetaEntries(batch.getWorldNodes().get(world));
        }
    }

    @Override
    public MetadataBatch getAllMetadata( ) {
        MetadataBatch.Builder builder = MetadataBatch.builder();
        for (MetadataEntry e : getInternalMetadataEntries()) {
            builder.addGlobalEntry(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, LocalGroupWorldData> worldEntry : worldDataMap.entrySet()) {
            for (MetadataEntry metaEntry : worldEntry.getValue().getInternalMetadataEntries()) {
                builder.addEntry(metaEntry.getKey(), metaEntry.getValue(), worldEntry.getKey());
            }
        }
        return builder.build();
    }

    @Override
    public int compareTo(PermissionGroup other) {
        Preconditions.checkNotNull(other, "other");
        return other.getPriority() - priority;
    }

    @Override
    public Set<PermissionGroup> getParents( ) {
        return Sets.newTreeSet(parents); // Defensive copy, natural ordering.
    }

    @Override
    public Set<PermissionGroup> getChildren( ) {
        return Sets.newTreeSet(children);
    }

    @Override
    public Set<PermissionGroup> getAllParents( ) {
        Set<PermissionGroup> ret = Sets.newTreeSet();
        for (PermissionGroup g : getParents()) {
            ret.add(g);
            ret.addAll(g.getAllParents());
        }
        return ret;
    }

    @Override
    public boolean addParent(PermissionGroup parent) {
        Preconditions.checkNotNull(parent, "parent");
        boolean success = parents.add(parent);
        if (success) {
            if (parent instanceof LocalGroup) {
                ((LocalGroup) parent).addChild(this);
            } else {
                Bukkit.getLogger().warning("Invalid group to add a child to: " + parent.getClass().getName() + " (" + parent.getName() + ")");
            }
            recalculatePlayerGroupData();
            groupDataSource.addParent(parent);
        }
        return success;
    }

    @Override
    public boolean removeParent(PermissionGroup parent) {
        Preconditions.checkNotNull(parent, "parent");
        boolean success = parents.remove(parent);
        if (success) {
            if (parent instanceof LocalGroup) {
                ((LocalGroup) parent).removeChild(this);
            } else {
                Bukkit.getLogger().warning("Invalid group to remove a child from: " + parent.getClass().getName() + " (" + parent.getName() + ")");
            }
            recalculatePlayerGroupData();
            groupDataSource.removeParent(parent);
        }
        return success;
    }

    private boolean addChild(PermissionGroup child) {
        Preconditions.checkNotNull(child, "child");
        boolean success = children.add(child);
        if (success) {
            groupDataSource.addChild(child);
        }
        return success;
    }

    private boolean removeChild(PermissionGroup child) {
        Preconditions.checkNotNull(child, "child");
        boolean success = children.remove(child);
        if (success) {
            groupDataSource.removeChild(child);
        }
        return success;
    }

    @Override
    public String getName( ) {
        return name;
    }

    @Override
    public int getPriority( ) {
        int tempPriority = priority;
        if (tempPriority == -1) {
            synchronized (priorityLock) {
                tempPriority = priority;
                if (tempPriority == -1) {
                    tempPriority = priority = groupDataSource.getPriority();
                }
            }
        }
        return tempPriority;
    }

    @Override
    public UUID getUniqueId( ) {
        return uniqueId;
    }

    @Override
    protected void registerTempPermission(String node, long timeInMillis) {
        tempManager.registerGlobalTemporaryPermission(this, new TemporaryPermissionEntry(node, System.currentTimeMillis() + timeInMillis));
    }

    @Override
    protected void cancelTempPermission(String node) {
        tempManager.cancelGlobalTemporaryPermission(this, node);
    }

    public void addUserToGroup(LocalUser user) {
        usersInGroup.add(user);
    }

    public void removeUserFromGroup(LocalUser user) {
        usersInGroup.remove(user);
    }
}
