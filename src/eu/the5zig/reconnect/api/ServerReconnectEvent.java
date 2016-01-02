package eu.the5zig.reconnect.api;

import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Event;

public class ServerReconnectEvent extends Event {

	private final ProxiedPlayer player;
	private ServerInfo target;
	private boolean cancelled;

	public ServerReconnectEvent(ProxiedPlayer player, ServerInfo target) {
		this.player = player;
		this.target = target;
	}

	public ProxiedPlayer getPlayer() {
		return this.player;
	}

	public ServerInfo getTarget() {
		return this.target;
	}

	public boolean isCancelled() {
		return this.cancelled;
	}

	public void setCancelled(boolean cancelled) {
		this.cancelled = cancelled;
	}

	public String toString() {
		return "ServerReconnectEvent(player=" + this.getPlayer() + ", target=" + this.getTarget() + ", cancelled=" + this.isCancelled() + ")";
	}

}
