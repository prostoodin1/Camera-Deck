package io.github.prostoodin1.cameradeck;

/** Pure policy; callers supply a monotonic clock. No frame processing in standby. */
final class PowerPolicy {
    enum Plan { STANDBY, PREVIEW, RECORD, RECORD_PREVIEW }
    private volatile long remoteUntil, localUntil, operationUntil;
    volatile boolean eco = true;
    void remote(long now) { remoteUntil = now + 4000; }
    void local(long now) { localUntil = now + 1500; }
    void hideLocal() { localUntil = 0; }
    void operation(long now) { operationUntil = now + 3000; }
    boolean preview(long now) { return now < Math.max(remoteUntil, Math.max(localUntil, operationUntil)); }
    int fps() { return eco ? 5 : 10; }
    int quality() { return eco ? 60 : 72; }
    Plan plan(long now, boolean recording) {
        boolean view = preview(now);
        return recording ? (view ? Plan.RECORD_PREVIEW : Plan.RECORD) : (view ? Plan.PREVIEW : Plan.STANDBY);
    }
}
