package eu.the5zig.reconnect.net;

import com.google.common.base.Objects;
import eu.the5zig.reconnect.Reconnect;
import net.md_5.bungee.ServerConnection;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.Util;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.event.ServerDisconnectEvent;
import net.md_5.bungee.api.event.ServerKickEvent;
import net.md_5.bungee.chat.ComponentSerializer;
import net.md_5.bungee.connection.CancelSendSignal;
import net.md_5.bungee.connection.DownstreamBridge;
import net.md_5.bungee.netty.ChannelWrapper;
import net.md_5.bungee.protocol.packet.Kick;

/**
 * Our own implementation of the BungeeCord DownstreamBridge.<br>
 * Inside here, all packets going from the Minecraft Server to the Minecraft Client are being handled.
 */
public class ReconnectBridge extends DownstreamBridge {

	private final ProxyServer bungee;
	private final UserConnection user;
	private final ServerConnection server;

	public ReconnectBridge(ProxyServer bungee, UserConnection user, ServerConnection server) {
		super(bungee, user, server);
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

		// Fire ServerReconnectEvent and give plugins the possibility to cancel server reconnecting.
		if (!Reconnect.getInstance().fireServerReconnectEvent(user, server)) {
			// Invoke default behaviour if event has been cancelled.

			ServerInfo def = bungee.getServerInfo(user.getPendingConnection().getListener().getFallbackServer());
			if (server.getInfo() != def) {
				server.setObsolete(true);
				user.connectNow(def);
				user.sendMessage(bungee.getTranslation("server_went_down"));
			} else {
				user.disconnect(Util.exception(t));
			}
		} else {
			// Otherwise, reconnect the User if he is still online.
			Reconnect.getInstance().reconnectIfOnline(user, server);
		}
	}

	@Override
	public void disconnected(ChannelWrapper channel) throws Exception {
		// Usually, BungeeCord would disconnect the Player with a "Connection Lost" message whenever it loses
		// the connection to the server. We are more kind and wait for the server to restart.
		//
		// This method is getting called always, but sometimes it is called after the user has been already disconnected,
		// that's why we need to override the other methods as well.

		// We need still to remove the User from the server, otherwise errors might occur when reconnecting.
		server.getInfo().removePlayer(user);

		// Fire ServerReconnectEvent and give plugins the possibility to cancel server reconnecting.
		if (!Reconnect.getInstance().fireServerReconnectEvent(user, server)) {
			// Invoke default behaviour if event has been cancelled.

			// BungeeCord reconnect handling.
			if (bungee.getReconnectHandler() != null) {
				bungee.getReconnectHandler().setServer(user);
			}
			// Disconnect the User.
			if (!server.isObsolete()) {
				user.disconnect(bungee.getTranslation("lost_connection"));
			}
			// Call ServerDisconnectEvent
			bungee.getPluginManager().callEvent(new ServerDisconnectEvent(user, server.getInfo()));
		} else {
			// Otherwise, reconnect the User if he is still online.
			Reconnect.getInstance().reconnectIfOnline(user, server);
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
		ServerKickEvent event = bungee.getPluginManager().callEvent(
				new ServerKickEvent(user, server.getInfo(), ComponentSerializer.parse(kick.getMessage()), def, ServerKickEvent.State.CONNECTED));
		if (event.isCancelled() && event.getCancelServer() != null) {
			user.connectNow(event.getCancelServer());
		} else {
			// As always, we fire a ServerReconnectEvent and give plugins the possibility to cancel server reconnecting.
			if (!Reconnect.getInstance().fireServerReconnectEvent(user, server)) {
				// Invoke default behaviour if event has been cancelled and disconnect the player.
				user.disconnect0(event.getKickReasonComponent());
			} else {
				// Otherwise, reconnect the User if he is still online.
				Reconnect.getInstance().reconnectIfOnline(user, server);
			}
		}
		server.setObsolete(true);

		// Throw Exception so that the Packet won't be send to the Minecraft Client.
		throw CancelSendSignal.INSTANCE;
	}
}