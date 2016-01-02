package eu.the5zig.reconnect.net;

import com.google.common.base.Objects;
import eu.the5zig.reconnect.Reconnect;
import net.md_5.bungee.ServerConnection;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.Util;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.event.ServerKickEvent;
import net.md_5.bungee.chat.ComponentSerializer;
import net.md_5.bungee.connection.CancelSendSignal;
import net.md_5.bungee.connection.DownstreamBridge;
import net.md_5.bungee.protocol.packet.Kick;

/**
 * Our own implementation of the BungeeCord DownstreamBridge.<br>
 * Inside here, all packets going from the Minecraft Server to the Minecraft Client are being handled.
 */
public class ReconnectBridge extends DownstreamBridge {

	private final Reconnect instance;
	private final ProxyServer bungee;
	private final UserConnection user;
	private final ServerConnection server;

	public ReconnectBridge(Reconnect instance, ProxyServer bungee, UserConnection user, ServerConnection server) {
		super(bungee, user, server);
		this.instance = instance;
		this.bungee = bungee;
		this.user = user;
		this.server = server;
	}

	@Override
	public void exception(Throwable t) throws Exception {
		// Usually, BungeeCord would reconnect the Player to the fallback server or kick him if not
		// Fallback Server is available, when an Exception between the BungeeCord and the Minecraft Server
		// occurs. We override this Method so that we can try to reconnect the client instead.

		if (server.isObsolete()) {
			// do not perform any actions if the user has already moved
			return;
		}
		// setObsolete so that DownstreamBridge.disconnected(ChannelWrapper) won't be called.
		server.setObsolete(true);

		// Fire ServerReconnectEvent and give plugins the possibility to cancel server reconnecting.
		if (!instance.fireServerReconnectEvent(user, server)) {
			// Invoke default behaviour if event has been cancelled.

			ServerInfo def = bungee.getServerInfo(user.getPendingConnection().getListener().getFallbackServer());
			if (server.getInfo() != def) {
				user.connectNow(def);
				user.sendMessage(bungee.getTranslation("server_went_down"));
			} else {
				user.disconnect(Util.exception(t));
			}
		} else {
			// Otherwise, reconnect the User if he is still online.
			instance.reconnectIfOnline(user, server);
		}
	}

	@Override
	public void handle(Kick kick) throws Exception {
		// This method is called whenever a Kick-Packet is sent from the Minecraft Server to the Minecraft Client.

		ServerInfo def = bungee.getServerInfo(user.getPendingConnection().getListener().getFallbackServer());
		if (Objects.equal(server.getInfo(), def)) {
			def = null;
		}
		// Call ServerKickEvent
		ServerKickEvent event = bungee.getPluginManager().callEvent(new ServerKickEvent(user, server.getInfo(), ComponentSerializer.parse(kick.getMessage()), def, ServerKickEvent.State.CONNECTED));
		if (event.isCancelled() && event.getCancelServer() != null) {
			user.connectNow(event.getCancelServer());
		} else {
			String kickMessage = ChatColor.stripColor(BaseComponent.toLegacyText(ComponentSerializer.parse(kick.getMessage()))); // needs to be parsed like that...
			// doReconnect indicates whether the player should be reconnected or not after he has been kicked. Only if the kick reason matches the one that has been
			// pre-defined on the config, we allow him to reconnect.
			boolean doReconnect = false;
			if (instance.getShutdownMessage() != null && instance.getShutdownMessage().equals(kickMessage)) {
				doReconnect = true;
			} else if (instance.getShutdownMessage() == null && instance.getShutdownPattern() != null) {
				try {
					doReconnect = instance.getShutdownPattern().matcher(kickMessage).matches();
				} catch (Exception e) {
					instance.getLogger().warning("Could not match shutdown-pattern " + instance.getShutdownPattern().pattern());
				}
			}

			// As always, we fire a ServerReconnectEvent and give plugins the possibility to cancel server reconnecting.
			if (!doReconnect || !instance.fireServerReconnectEvent(user, server)) {
				// Invoke default behaviour if event has been cancelled and disconnect the player.
				user.disconnect0(event.getKickReasonComponent());
			} else {
				// Otherwise, reconnect the User if he is still online.
				instance.reconnectIfOnline(user, server);
			}
		}
		server.setObsolete(true);

		// Throw Exception so that the Packet won't be send to the Minecraft Client.
		throw CancelSendSignal.INSTANCE;
	}
}
