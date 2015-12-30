package eu.the5zig.reconnect;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.util.internal.PlatformDependent;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import net.md_5.bungee.BungeeServerInfo;
import net.md_5.bungee.ServerConnection;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.Title;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.netty.PipelineUtils;
import net.md_5.bungee.protocol.packet.KeepAlive;
import eu.the5zig.reconnect.net.BasicChannelInitializer;

public class ReconnectTask {

	private static final Random random = new Random();
	private static final TextComponent EMPTY = new TextComponent("");

	private ProxyServer bungee;
	private UserConnection user;
	private ServerConnection server;
	private BungeeServerInfo target;

	private int tries;

	public ReconnectTask(ProxyServer bungee, UserConnection user, ServerConnection server) {
		this.bungee = bungee;
		this.user = user;
		this.server = server;
		this.target = server.getInfo();
	}

	/**
	 * Tries to reconnect the User to the specified Server. In case that fails, this method will be executed again
	 * after a short timeout.
	 */
	public void tryReconnect() {
		if (tries + 1 > Reconnect.getInstance().getMaxReconnectTries()) {
			// If we have reached the maximum reconnect limit, proceed BungeeCord-like.
			Reconnect.getInstance().cancelReconnectTask(user.getUniqueId());

			ServerInfo def = bungee.getServerInfo(user.getPendingConnection().getListener().getFallbackServer());
			if (target != def) {
				// If the fallback-server is not the same server we tried to reconnect to, send the user to that one instead.
				server.setObsolete(true);
				user.connectNow(def);
				user.sendMessage(bungee.getTranslation("server_went_down"));
			} else {
				// Otherwise, disconnect the user with a "Lost Connection"-message.
				user.disconnect(bungee.getTranslation("lost_connection"));
			}
			return;
		}

		// If we are already connecting to a server, cancel the reconnect task.
		if (user.getPendingConnects().contains(target)) {
			Reconnect.getInstance().getLogger().warning("User already connecting to " + target);
			return;
		}
		// Add pending connection.
		user.getPendingConnects().add(target);

		tries++;
		// Send fancy Title
		if (!Reconnect.getInstance().getConnectingText().isEmpty()) {
			createReconnectTitle().send(user);
		}

		// Establish connection to the server.
		ChannelInitializer<Channel> initializer = new BasicChannelInitializer(bungee, user, target);
		ChannelFutureListener listener = future -> {
			if (future.isSuccess()) {
				// If reconnected successfully, remove from map and send another fancy title.
				Reconnect.getInstance().cancelReconnectTask(user.getUniqueId());

				if (!Reconnect.getInstance().getConnectingText().isEmpty()) {
					createConnectingTitle().send(user);
				}
			} else {
				future.channel().close();
				user.getPendingConnects().remove(target);

				// Send KeepAlive Packet so that the client won't
				user.unsafe().sendPacket(new KeepAlive(random.nextInt()));

				// Schedule next reconnect.
				bungee.getScheduler().schedule(Reconnect.getInstance(),
						() -> bungee.getScheduler().runAsync(Reconnect.getInstance(), () -> Reconnect.getInstance().reconnectIfOnline(user, server)),
						Reconnect.getInstance().getReconnectMillis(), TimeUnit.MILLISECONDS);
			}
		};

		// Create a new Netty Bootstrap that contains the ChannelInitializer and the ChannelFutureListener.
		Bootstrap b = new Bootstrap().channel(PipelineUtils.getChannel()).group(server.getCh().getHandle().eventLoop()).handler(initializer).option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
				Reconnect.getInstance().getReconnectTimeout()).remoteAddress(target.getAddress());

		// Windows is bugged, multi homed users will just have to live with random connecting IPs
		if (user.getPendingConnection().getListener().isSetLocalAddress() && !PlatformDependent.isWindows()) {
			b.localAddress(user.getPendingConnection().getListener().getHost().getHostString(), 0);
		}
		b.connect().addListener(listener);
	}

	/**
	 * Creates a Title containing the reconnect-text.
	 *
	 * @return a Title that can be send to the player.
	 */
	private Title createReconnectTitle() {
		Title title = ProxyServer.getInstance().createTitle();
		title.title(EMPTY);
		String dots = "";
		for (int i = 0, max = tries % 4; i < max; i++) {
			dots += ".";
		}
		title.subTitle(new TextComponent(Reconnect.getInstance().getReconnectText() + dots));
		// Stay at least as long as the longest possible connect-time can be.
		title.stay((Reconnect.getInstance().getReconnectMillis() + Reconnect.getInstance().getReconnectTimeout() + 1000) / 1000 * 20);
		title.fadeIn(0);
		title.fadeOut(0);

		return title;
	}

	/**
	 * Creates a Title containing the connecting-text.
	 *
	 * @return a Title that can be send to the player.
	 */
	private Title createConnectingTitle() {
		Title title = ProxyServer.getInstance().createTitle();
		title.title(EMPTY);
		title.subTitle(new TextComponent(Reconnect.getInstance().getConnectingText()));
		title.stay(20);
		title.fadeIn(10);
		title.fadeOut(10);

		return title;
	}

}
