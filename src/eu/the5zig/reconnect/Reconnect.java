package eu.the5zig.reconnect;

import com.google.common.io.ByteStreams;
import eu.the5zig.reconnect.api.ServerReconnectEvent;
import eu.the5zig.reconnect.net.ReconnectBridge;
import net.md_5.bungee.ServerConnection;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.netty.ChannelWrapper;
import net.md_5.bungee.netty.HandlerBoss;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class Reconnect extends Plugin implements Listener {

	private static Reconnect instance;

	private String reconnectingTitle = "&7Reconnecting{%dots%}";
	private String reconnectingActionBar = "&a&lPlease do not leave! &7Reconnecting to server{%dots%}";
	private String connectingTitle = "&aConnecting..";
	private String connectingActionBar = "&7Connecting you to the server..";
	private String failedTitle = "&cReconnecting failed!";
	private String failedActionBar = "&eYou have been moved to the fallback server!";
	private int maxReconnectTries = 20;
	private int reconnectMillis = 1000;
	private int reconnectTimeout = 5000;
	private List<String> ignoredServers = new ArrayList<String>();

	/**
	 * A HashMap containing all reconnect tasks.
	 */
	private HashMap<UUID, ReconnectTask> reconnectTasks = new HashMap<UUID, ReconnectTask>();

	@Override
	public void onEnable() {
		instance = this;

		// register Listener
		getProxy().getPluginManager().registerListener(this, this);

		// load config
		loadConfig();
	}

	/**
	 * Tries to load the config from the config file or creates a default config if the file does not exist.
	 */
	private void loadConfig() {
		try {
			if (!getDataFolder().exists() && !getDataFolder().mkdir()) {
				throw new IOException("Could not create plugin directory!");
			}
			File configFile = new File(getDataFolder(), "config.yml");
			if (configFile.exists()) {
				Configuration configuration = ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile);
				reconnectingTitle = configuration.getString("reconnecting-text.title", reconnectingTitle);
				reconnectingActionBar = configuration.getString("reconnecting-text.actionbar", reconnectingActionBar);
				connectingTitle = configuration.getString("connecting-text.title", connectingTitle);
				connectingActionBar = configuration.getString("connecting-text.actionbar", connectingActionBar);
				failedTitle = configuration.getString("failed-text.title", failedTitle);
				failedActionBar = configuration.getString("failed-text.actionbar", failedActionBar);
				maxReconnectTries = Math.max(configuration.getInt("max-reconnect-tries", maxReconnectTries), 1);
				reconnectMillis = Math.max(configuration.getInt("reconnect-time", reconnectMillis), 0);
				reconnectTimeout = Math.max(configuration.getInt("reconnect-timeout", reconnectTimeout), 1000);
				ignoredServers = configuration.getStringList("ignored-servers");
			} else {
				if (!configFile.createNewFile()) {
					throw new IOException("Could not create default config!");
				}
				try (InputStream is = getResourceAsStream("config.yml");
						OutputStream os = new FileOutputStream(configFile)) {
					ByteStreams.copy(is, os);
				}
			}
		} catch (IOException e) {
			getLogger().warning("Could not load config, using default values...");
			e.printStackTrace();
		}
	}

	@EventHandler
	public void onServerSwitch(ServerSwitchEvent event) {
		// We need to override the Downstream class of each user so that we can override the disconnect methods of it.
		// ServerSwitchEvent is called just right after the Downstream Bridge has been initialized, so we simply can
		// instantiate here our own implementation of the DownstreamBridge
		//
		// @see net.md_5.bungee.ServerConnector#L249

		ProxyServer bungee = getProxy();
		UserConnection user = (UserConnection) event.getPlayer();
		ServerConnection server = user.getServer();
		ChannelWrapper ch = server.getCh();

		ReconnectBridge bridge = new ReconnectBridge(bungee, user, server);
		ch.getHandle().pipeline().get(HandlerBoss.class).setHandler(bridge);
	}

	/**
	 * Checks whether the current server should be ignored and fires a ServerReconnectEvent afterwards.
	 *
	 * @param user   The User that should be reconnected.
	 * @param server The Server the User should be reconnected to.
	 * @return true, if the ignore list does not contain the server and the event hasn't been canceled.
	 */
	public boolean fireServerReconnectEvent(UserConnection user, ServerConnection server) {
		if (ignoredServers.contains(server.getInfo().getName())) {
			return false;
		}
		ServerReconnectEvent event = getProxy().getPluginManager().callEvent(new ServerReconnectEvent(user, server.getInfo()));
		return !event.isCancelled();
	}

	/**
	 * Checks if a UserConnection is still online.
	 *
	 * @param user The User that should be checked.
	 * @return true, if the UserConnection is still online.
	 */
	public boolean isUserOnline(UserConnection user) {
		return getProxy().getPlayer(user.getUniqueId()) != null;
	}

	/**
	 * Reconnects a User to a Server, as long as the user is currently online. If he isn't, his reconnect task (if he had one)
	 * will be canceled.
	 *
	 * @param user   The User that should be reconnected.
	 * @param server The Server the User should be connected to.
	 */
	public void reconnectIfOnline(UserConnection user, ServerConnection server) {
		if (isUserOnline(user)) {
			if (!isReconnecting(user.getUniqueId())) {
				reconnect(user, server);
			}
		} else {
			cancelReconnectTask(user.getUniqueId());
		}
	}

	/**
	 * Reconnects the User without checking whether he's online or not.
	 *
	 * @param user   The User that should be reconnected.
	 * @param server The Server the User should be connected to.
	 */
	private void reconnect(UserConnection user, ServerConnection server) {
		ReconnectTask reconnectTask = reconnectTasks.get(user.getUniqueId());
		if (reconnectTask == null) {
			reconnectTasks.put(user.getUniqueId(), reconnectTask = new ReconnectTask(getProxy(), user, server));
		}
		reconnectTask.tryReconnect();
	}

	/**
	 * Removes a reconnect task from the main HashMap
	 *
	 * @param uuid The UniqueId of the User.
	 */
	void cancelReconnectTask(UUID uuid) {
		reconnectTasks.remove(uuid);
	}

	/**
	 * Checks whether a User has got a reconnect task.
	 *
	 * @param uuid The UniqueId of the User.
	 * @return true, if there is a task that tries to reconnect the User to a server.
	 */
	public boolean isReconnecting(UUID uuid) {
		return reconnectTasks.containsKey(uuid);
	}

	public String getReconnectingTitle() {
		return ChatColor.translateAlternateColorCodes('&', reconnectingTitle);
	}
	
	public String getReconnectingActionBar() {
		return ChatColor.translateAlternateColorCodes('&', reconnectingActionBar);
	}

	public String getConnectingTitle() {
		return ChatColor.translateAlternateColorCodes('&', connectingTitle);
	}
	
	public String getConnectingActionBar() {
		return ChatColor.translateAlternateColorCodes('&', connectingActionBar);
	}
	
	public String getFailedTitle() {
		return ChatColor.translateAlternateColorCodes('&', failedTitle);
	}
	
	public String getFailedActionBar() {
		return ChatColor.translateAlternateColorCodes('&', failedActionBar);
	}

	public int getMaxReconnectTries() {
		return maxReconnectTries;
	}

	public int getReconnectMillis() {
		return reconnectMillis;
	}

	public int getReconnectTimeout() {
		return reconnectTimeout;
	}

	/**
	 * @return the current instance of this Plugin.
	 */
	public static Reconnect getInstance() {
		return instance;
	}

}
