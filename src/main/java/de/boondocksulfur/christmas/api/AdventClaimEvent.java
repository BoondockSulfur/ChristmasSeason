package de.boondocksulfur.christmas.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired before a player receives an advent calendar reward. Cancel to deny the claim. */
public class AdventClaimEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final int day;
    private boolean cancelled;

    public AdventClaimEvent(Player player, int day) {
        this.player = player;
        this.day = day;
    }

    public Player getPlayer() { return player; }
    /** Calendar day (1-24 by default). */
    public int getDay() { return day; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
