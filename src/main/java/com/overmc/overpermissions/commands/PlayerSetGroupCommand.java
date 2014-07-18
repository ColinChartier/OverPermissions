package com.overmc.overpermissions.commands;

import static com.overmc.overpermissions.Messages.*;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import com.overmc.overpermissions.Messages;
import com.overmc.overpermissions.OverPermissions;
import com.overmc.overpermissions.api.PermissionGroup;
import com.overmc.overpermissions.api.PermissionUser;
import com.overmc.overpermissions.api.events.PlayerGroupSetByPlayerEvent;
import com.overmc.overpermissions.api.events.PlayerGroupSetEvent;

// ./playersetgroup [player] [group]
public class PlayerSetGroupCommand implements TabExecutor {
    private final OverPermissions plugin;

    public PlayerSetGroupCommand(OverPermissions plugin) {
        this.plugin = plugin;
    }

    public PlayerSetGroupCommand register( ) {
        PluginCommand command = this.plugin.getCommand("playersetgroup");
        command.setExecutor(this);
        command.setTabCompleter(this);
        return this;
    }

    @Override
    public boolean onCommand(final CommandSender sender, Command command, String label, final String[] args) {
        if (!sender.hasPermission(command.getPermission())) {
            sender.sendMessage(ERROR_NO_PERMISSION);
            return true;
        }
        if ((args.length != 2)) {
            sender.sendMessage(Messages.getUsage(command));
            return true;
        }
        final String victim = args[0];
        final String groupName = args[1];
        if (!plugin.getGroupManager().doesGroupExist(groupName)) {
            sender.sendMessage(Messages.format(ERROR_GROUP_NOT_FOUND, groupName));
            return true;
        }
        final PermissionGroup group = plugin.getGroupManager().getGroup(groupName);
        if (!plugin.getUserManager().canUserExist(victim)) {
            sender.sendMessage(Messages.format(ERROR_PLAYER_LOOKUP_FAILED, victim));
            return true;
        }
        final PermissionUser user = plugin.getUserManager().getPermissionUser(victim);
        PlayerGroupSetEvent event;
        if (sender instanceof Player) {
            event = new PlayerGroupSetByPlayerEvent(user.getName(), group.getName(), (Player) sender);
        } else {
            event = new PlayerGroupSetEvent(user.getName(), group.getName());
        }
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return true;
        }
        plugin.getExecutor().submit(new Runnable() {
            @Override
            public void run( ) {
                user.setParent(group);
                sender.sendMessage(Messages.format(SUCCESS_PLAYER_SET_GROUP, user.getName(), group.getName()));
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        ArrayList<String> ret = new ArrayList<>();
        if (!sender.hasPermission(command.getPermission())) {
            sender.sendMessage(ERROR_NO_PERMISSION);
            return ret;
        }
        int index = args.length - 1;
        String value = args[index].toLowerCase();
        if (index == 0) {
            CommandUtils.loadPlayers(value, ret);
        } else if (index == 1) {
            CommandUtils.loadGroups(plugin.getGroupManager(), value, ret);
        }
        return ret;
    }
}
