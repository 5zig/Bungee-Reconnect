package eu.the5zig.reconnect.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import net.md_5.bungee.BungeeServerInfo;
import net.md_5.bungee.ServerConnector;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.netty.HandlerBoss;
import net.md_5.bungee.netty.PipelineUtils;
import net.md_5.bungee.protocol.MinecraftDecoder;
import net.md_5.bungee.protocol.MinecraftEncoder;
import net.md_5.bungee.protocol.Protocol;

public class BasicChannelInitializer extends ChannelInitializer<Channel> {

	private final ProxyServer bungee;
	private final UserConnection user;
	private final BungeeServerInfo target;

	public BasicChannelInitializer(ProxyServer bungee, UserConnection user, BungeeServerInfo target) {
		this.bungee = bungee;
		this.user = user;
		this.target = target;
	}

	@Override
	protected void initChannel(Channel ch) throws Exception {
		PipelineUtils.BASE.initChannel(ch);
		ch.pipeline().addAfter(PipelineUtils.FRAME_DECODER, PipelineUtils.PACKET_DECODER, new MinecraftDecoder(Protocol.HANDSHAKE, false, user.getPendingConnection().getVersion()));
		ch.pipeline().addAfter(PipelineUtils.FRAME_PREPENDER, PipelineUtils.PACKET_ENCODER, new MinecraftEncoder(Protocol.HANDSHAKE, false, user.getPendingConnection().getVersion()));
		ch.pipeline().get(HandlerBoss.class).setHandler(new ServerConnector(bungee, user, target));
	}

}
