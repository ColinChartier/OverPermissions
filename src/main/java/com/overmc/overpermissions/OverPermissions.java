package com.overmc.overpermissions;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.*;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.overmc.overpermissions.commands.*;
import com.overmc.overpermissions.metrics.MetricsLite;

public class OverPermissions extends JavaPlugin {
	private SQLManager sqlManager;
	private GroupManager groupManager;
	private TimedPermissionManager tempManager;
	private OverPermissionsAPI permissionsAPI;
	private MetricsLite metrics;
	private String defaultGroup;
	private int defaultGroupId;

	private boolean failureStarting = false;
	private static HashMap<Player, PlayerPermissionData> players = new HashMap<Player, PlayerPermissionData>();
	private static HashMap<Player, Future<PlayerPermissionData>> playerFutures = new HashMap<Player, Future<PlayerPermissionData>>();
	public static final ExecutorService exec = Executors.newCachedThreadPool();

	@Override
	public void onEnable( ) {
		try {
			initConfig();
			initKickOnFail();
			initManagers();
			initCommands();
			registerEvents();
			initAPI();
			initMetrics();
			initPlayers();
			getLogger().info(ChatColor.GREEN + "Successfully enabled!");
		} catch (StartException e) {
			failureStarting = true;
			getLogger().severe(ChatColor.RED + "Failed to start: " + e.getSimpleMessage());
		} catch (Throwable t) {
			t.printStackTrace();
			failureStarting = true;
		}
	}

	@Override
	public void onDisable( ) {
		getLogger().info("disabled.");
		deinitPlayers();
	}

	private void deinitPlayers( ) {
		for (Player p : Bukkit.getOnlinePlayers()) {
			deinitPlayer(p);
		}
	}

	private void initConfig( ) throws Throwable {
		saveDefaultConfig();
		getConfig().options().copyDefaults(true);
		saveConfig();
		reloadConfig();
	}

	private void initKickOnFail( ) throws Throwable {
		getServer().getPluginManager().registerEvents(new KickOnFailListener(this), this);
	}

	private void initManagers( ) throws Throwable {
		String type = getConfig().getString("sql.type", "sqlite");
		if (type.equalsIgnoreCase("sqlite")) {
			// TODO sqlManager = new SQLiteManager(this)
		} else if (type.equalsIgnoreCase("mysql")) {
			sqlManager = new MySQLManager(this,
					"jdbc:mysql://" + getConfig().getString("sql.address", "localhost") + "/",
					getConfig().getString("sql.dbname", "OverPermissions"),
					getConfig().getString("sql.dbusername", "root"),
					getConfig().getString("sql.dbpassword", ""));
		} else {
			getLogger().warning("Type value " + type + " wasn't recognized. Defaulting to sqlite.");
		}
		initDefaultGroup();
		tempManager = new TimedPermissionManager(this);
		groupManager = new GroupManager(this);
		groupManager.recalculateGroups();
	}

	private void initDefaultGroup( ) {
		defaultGroup = getConfig().getString("default-group", "default");
		if (sqlManager.getGroupId(defaultGroup) < 0) { // group doesn't exist.
			sqlManager.setGroup(defaultGroup, 0, -1);
			getLogger().info("Successfully created default group: " + defaultGroup);
		}
		defaultGroupId = sqlManager.getGroupId(defaultGroup);
		sqlManager.setGroup(defaultGroup, sqlManager.getGroupPriority(defaultGroupId), -1); // Force default group to global, things would seriously mess up otherwise.
	}

	private void initCommands( ) throws Throwable {
		new GroupCreateCommand(this).register();
		new GroupDeleteCommand(this).register();
		new GroupSetMetaCommand(this).register();
		new GroupAddCommand(this).register();
		new GroupAddTempCommand(this).register();
		new GroupAddParentCommand(this).register();
		new GroupRemoveParentCommand(this).register();
		new GroupRemoveCommand(this).register();

		new PlayerSetGroupCommand(this).register();
		new PlayerAddGroupCommand(this).register();
		new PlayerPromoteCommand(this).register();
		new PlayerSetMetaCommand(this).register();
		new PlayerAddCommand(this).register();
		new PlayerAddTempCommand(this).register();
		new PlayerRemoveCommand(this).register();
		new PlayerCheckCommand(this).register();

		new OverPermissionsCommand(this).register();
	}

	private void registerEvents( ) {
		getServer().getPluginManager().registerEvents(new GeneralListener(this), this);
	}

	private void initAPI( ) {
		permissionsAPI = new OverPermissionsAPI(this);
	}

	private void initMetrics( ) {
		try {
			metrics = new MetricsLite(this);
			metrics.start();
			getLogger().info("Successfully connected to metrics!");
		} catch (IOException e) {
			getLogger().warning("Failed to connect to the metrics server!");
			e.printStackTrace();
		}
	}

	private void initPlayers( ) {
		for (Player p : Bukkit.getOnlinePlayers()) {
			initPlayer(p);
		}
	}

	/**
	 * Only use this if you know what you're doing, the API layer is much safer.
	 * 
	 * @see #getAPI()
	 */
	public SQLManager getSQLManager( ) {
		return sqlManager;
	}

	/**
	 * Only use this if you know what you're doing, the API layer is much safer.
	 * 
	 * @see #getAPI()
	 */
	public GroupManager getGroupManager( ) {
		return groupManager;
	}

	/**
	 * Only use this if you know what you're doing, the API layer is much safer.
	 * 
	 * @see #getAPI()
	 */
	public TimedPermissionManager getTempManager( ) {
		return tempManager;
	}

	/**
	 * Only use this if you know what you're doing, the API layer is much safer.
	 * 
	 * @see #getAPI()
	 */
	public PlayerPermissionData getPlayerPermissions(Player player) {
		try {
			PlayerPermissionData d = players.get(player);
			if (d == null) {
				initPlayer(player);
				Future<PlayerPermissionData> future = playerFutures.remove(player);
				PlayerPermissionData playerData = future.get();
				players.put(player, playerData);
				return playerData;
			}
			return d;
		} catch (Throwable t) {
			t.printStackTrace();
		}
		return null;
	}

	protected void initPlayer(Player player) {
		if (players.containsKey(player) || playerFutures.containsKey(player)) {
			return;
		}
		tempManager.init(player);
		playerFutures.put(player, getPlayerFuture(player));
	}

	protected Future<PlayerPermissionData> getPlayerFuture(final Player player) {
		return exec.submit(new Callable<PlayerPermissionData>() {
			@Override
			public PlayerPermissionData call( ) throws Exception {
				PlayerPermissionData playerData = new PlayerPermissionData(OverPermissions.this, sqlManager.getPlayerId(player.getName(), true), sqlManager.getWorldId(player.getWorld().getName(), true),
						player);
				playerData.recalculateGroups();
				playerData.recalculatePermissions();
				playerData.recalculateMeta();
				return playerData;
			}
		});
	}

	protected void deinitPlayer(Player player) {
		tempManager.deinit(player);
		if (players.containsKey(player)) {
			players.get(player).unset();
			players.remove(player);
		}
		playerFutures.remove(player);
	}

	// API
	public OverPermissionsAPI getAPI( ) {
		return permissionsAPI;
	}

	public boolean checkFailure( ) {
		return failureStarting;
	}

	public int getDefaultGroupId( ) {
		return defaultGroupId;
	}

	public String getDefaultGroup( ) {
		return defaultGroup;
	}

	public ExecutorService getExecutor( ) {
		return exec;
	}
}