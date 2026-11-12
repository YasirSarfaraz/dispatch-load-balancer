package com.freightfox.dispatch.service;

/** Outcome of an upsert: how many rows were inserted and how many existing rows were updated. */
public record UpsertResult(int inserted, int updated) {

    public int total() {
        return inserted + updated;
    }
}
