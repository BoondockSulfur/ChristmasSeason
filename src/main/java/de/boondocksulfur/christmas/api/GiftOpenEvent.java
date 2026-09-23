package de.boondocksulfur.christmas.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired when a player opens a gift chest for the first time. */
public class GiftOpenEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Location location;

    public GiftOpenEvent(Player player, Location location) {
        this.player = player;
        this.location = location;
    }

    public Player getPlayer() { return player; }
    public Location getLocation() { return location.clone(); }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
