package com.overmc.overpermissions.internal.commands;

import static com.overmc.overpermissions.internal.Messages.*;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.*;
import org.bukkit.entity.Player;

import com.overmc.overpermissions.events.GroupDeletionByPlayerEvent;
import com.overmc.overpermissions.events.GroupDeletionEvent;
import com.overmc.overpermissions.internal.Messages;
import com.overmc.overpermissions.internal.OverPermissions;

// ./groupdelete [group]
public class GroupDeleteCommand implements TabExecutor {
    private final OverPermissions plugin;

    public GroupDeleteCommand(OverPermissions plugin) {
        this.plugin = plugin;
    }

    public GroupDeleteCommand register( ) {
        PluginCommand command = plugin.getCommand("groupdelete");
        command.setExecutor(this);
        command.setTabCompleter(this);
        return this;
    }

    @Override
    public boolean onCommand(final CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(command.getPermission())) {
            sender.sendMessage(ERROR_NO_PERMISSION);
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(Messages.getUsage(command));
            return true;
        }
        final String groupName = args[0];
        if (!plugin.getGroupManager().doesGroupExist(groupName)) {
            sender.sendMessage(Messages.format(ERROR_GROUP_NOT_FOUND, groupName));
            return true;
        }
        if (plugin.getDefaultGroupName().equalsIgnoreCase(groupName)) {
            sender.sendMessage(Messages.format(ERROR_DELETE_DEFAULT_GROUP, groupName));
            return true;
        }
        GroupDeletionEvent event;
        if (sender instanceof Player) {
            event = new GroupDeletionByPlayerEvent(groupName, (Player) sender);
        } else {
            event = new GroupDeletionEvent(groupName);
        }
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return true;
        }
        plugin.getExecutor().submit(new Runnable() {
            @Override
            public void run( ) {
                if (plugin.getGroupManager().deleteGroup(groupName)) {
                    sender.sendMessage(Messages.format(SUCCESS_GROUP_DELETE, groupName));
                } else {
                    sender.sendMessage(Messages.format(ERROR_UNKNOWN));
                }
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        ArrayList<String> ret = new ArrayList<>();
        if (!sender.hasPermission(command.getPermission())) {
            return ret;
        }
        int index = args.length - 1;
        String value = args[index].toLowerCase();
        if (index == 0) {
            CommandUtils.loadGroups(plugin.getGroupManager(), value, ret);
        }
        return ret;
    }
}