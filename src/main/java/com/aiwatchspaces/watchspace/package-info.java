/**
 * Module <b>watchspace</b>. Room runtime kernel: per-room mutation lane, in-memory RoomState, sequence/stateVersion allocation, room_events ledger, lifecycle, membership, moderation, invites, settings, recovery, timers. Defines the RoomBroadcaster, RoomTickHandler and RoomLifecycleListener SPIs. Depends on playback, timeline, users; never on chat, ai, variations, analytics, recommendations or realtime.
 */
package com.aiwatchspaces.watchspace;
