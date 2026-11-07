package com.example.apimedlink.service;

import com.example.apimedlink.model.Slot;

/** The slot after a status change, and what the change was (so the caller audits it, or not when nothing changed). */
public record SlotChange(Slot slot, Change change) {

    public enum Change {
        /** The slot was already in the requested status: nothing was written. */
        NONE,
        OPENED,
        CLOSED
    }
}
