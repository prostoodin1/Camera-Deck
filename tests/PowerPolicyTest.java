package io.github.prostoodin1.cameradeck;

public final class PowerPolicyTest {
    static void expect(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        PowerPolicy policy = new PowerPolicy();
        expect(policy.eco && policy.fps() == 12);
        expect(policy.plan(10000, false) == PowerPolicy.Plan.STANDBY);
        policy.remote(10000);
        expect(policy.plan(13999, false) == PowerPolicy.Plan.PREVIEW);
        expect(policy.plan(14000, false) == PowerPolicy.Plan.STANDBY);
        expect(policy.plan(14000, true) == PowerPolicy.Plan.RECORD);
        policy.local(15000);
        expect(policy.plan(15000, true) == PowerPolicy.Plan.RECORD_PREVIEW);
        policy.hideLocal();
        expect(policy.plan(15001, true) == PowerPolicy.Plan.RECORD);
        policy.operation(16000);
        expect(policy.plan(18999, false) == PowerPolicy.Plan.PREVIEW);
        expect(policy.plan(19000, false) == PowerPolicy.Plan.STANDBY);
        policy.eco = false;
        expect(policy.fps() == 24 && policy.quality() == 72);
        policy.local(20000);
        expect(policy.plan(21499, false) == PowerPolicy.Plan.PREVIEW);
        expect(policy.plan(21500, false) == PowerPolicy.Plan.STANDBY);
        // Remote viewers are independent of the local UI leaving the foreground.
        policy.remote(22000); policy.local(22000); policy.hideLocal();
        expect(policy.plan(24000, false) == PowerPolicy.Plan.PREVIEW);
        expect(policy.plan(26000, true) == PowerPolicy.Plan.RECORD);
        System.out.println("Power policy transitions passed");
    }
}
