package de.boondocksulfur.christmas.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired after the event has been activated ({@code /xmas on}, schedule) or deactivated. */
public class XmasStateChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final boolean active;
    private final Cause cause;

    /** What triggered the change. */
    public enum Cause { COMMAND, SCHEDULE, API }

    public XmasStateChangeEvent(boolean active, Cause cause) {
        this.active = active;
        this.cause = cause;
    }

    public boolean isActive() { return active; }
    public Cause getCause() { return cause; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
