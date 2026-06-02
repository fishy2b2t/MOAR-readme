package dev.moar.travel;

import dev.moar.travel.bounce.BounceController;
import dev.moar.travel.bridge.TravelBaritoneBridge;
import dev.moar.travel.detour.DetourPlanner;
import dev.moar.travel.elytra.ElytraManager;
import dev.moar.travel.flight.FlightController;
import dev.moar.travel.highway.HighwayVerifier;
import dev.moar.travel.highway.IntegrityReport;
import dev.moar.travel.plan.HighwayCandidate;
import dev.moar.travel.plan.HighwayPlanner;
import dev.moar.travel.plan.HighwayRoute;
import dev.moar.travel.telemetry.TravelLog;
import dev.moar.travel.telemetry.TravelTelemetry;

/*? if >=26.1 {*//*
import net.minecraft.client.Minecraft;
*//*?} else {*/
import net.minecraft.client.MinecraftClient;
/*?}*/
/*? if >=26.1 {*//*
import net.minecraft.core.BlockPos;
*//*?} else {*/
import net.minecraft.util.math.BlockPos;
/*?}*/

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

// Travel mission state machine; owns phase progression and movement handoffs.
public final class TravelManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MOAR/Travel");

    private static final TravelManager INSTANCE = new TravelManager();
    public static TravelManager get() { return INSTANCE; }

    private final TravelState          state    = new TravelState();
    private final TravelBaritoneBridge bridge   = TravelBaritoneBridge.get();
    private final BounceController     bounce   = BounceController.get();
    private final FlightController     flight   = FlightController.get();
    private final HighwayPlanner       planner  = new HighwayPlanner();
    private final HighwayVerifier      verifier = HighwayVerifier.get();
    private final ElytraManager        elytra   = new ElytraManager();

    private int currentLegIndex = -1;

    // Bounce-leg exit to re-enter after a detour/resupply; null when none active.
    private BlockPos detourResumeExit;

    private int  settleTicks  = 0;  // yaw-hold countdown; 0 = not settling
    private int  settleYawDx  = 0;
    private int  settleYawDz  = 0;

    // ── Auto-resume ────────────────────────────────────────────────
    private int autoResumeTicks    = 0; // ticks until next re-plan; 0 = none pending
    private int autoResumeAttempts = 0; // consecutive retries since mission start
    private static final int AUTO_RESUME_DELAY_TICKS = 100; // 5 s between retries
    static final int MAX_AUTO_RESUME_ATTEMPTS = 10;          // cap on retry budget

    private TravelManager() {}

    // ──────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────

    public synchronized boolean start(TravelMission mission) {
        if (state.phase != TravelPhase.IDLE) {
            LOGGER.warn("start() rejected: phase={} (not IDLE)", state.phase);
            return false;
        }
        state.reset();
        verifier.clear();
        state.mission = mission;
        currentLegIndex = -1;
        detourResumeExit = null;
        autoResumeAttempts = 0;
        autoResumeTicks = 0;
        transition(TravelPhase.PLANNING, "user start: " + mission);
        return true;
    }

    public synchronized void stop() {
        if (state.phase == TravelPhase.IDLE) return;
        elytra.stop();
        autoResumeTicks = 0;  // cancel any pending auto-resume
        state.abortReason = "user stop";
        transition(TravelPhase.ABORTED, "user stop");
    }

    public synchronized void pause() {
        if (state.phase == TravelPhase.IDLE || state.phase == TravelPhase.PAUSED) return;
        state.pausedFromPhase = state.phase;
        releaseOwner(state.owner);
        state.owner = MovementOwner.NONE;
        TravelPhase from = state.phase;
        state.phase = TravelPhase.PAUSED;
        state.ticksInPhase = 0;
        state.lastTransitionReason = "user pause from " + from;
        TravelLog.get().recordTransition(missionId(), state.missionTicks, from, TravelPhase.PAUSED, state.lastTransitionReason);
    }

    public synchronized void resume() {
        if (state.phase == TravelPhase.PAUSED) {
            TravelPhase target = state.pausedFromPhase != null ? state.pausedFromPhase : TravelPhase.PLANNING;
            transition(target, "user resume");
            return;
        }
        // Re-plan to last destination after stop.
        if (state.phase == TravelPhase.IDLE && state.mission != null) {
            TravelMission m = state.mission;
            state.reset();
            verifier.clear();
            state.mission = m;
            currentLegIndex = -1;
            detourResumeExit = null;
            transition(TravelPhase.PLANNING, "user resume");
        }
    }

    // EC position for ElytraManager resupply.
    public synchronized void setEnderChestPos(BlockPos pos) { elytra.setEnderChestPos(pos); }
    public synchronized BlockPos getEnderChestPos()         { return elytra.getEnderChestPos(); }

    // Repair worn elytra without a travel mission; returns to IDLE when done.
    public synchronized boolean startStandaloneRepair() {
        if (state.phase != TravelPhase.IDLE) {
            LOGGER.warn("[Travel] startStandaloneRepair rejected: phase={}", state.phase);
            return false;
        }
        elytra.startRepair();
        transition(TravelPhase.ELYTRA_RESUPPLY, "standalone repair");
        return true;
    }

    public synchronized TravelTelemetry snapshot() {
        if (state.mission == null) return TravelTelemetry.idle();
        return new TravelTelemetry(
                state.mission.id,
                state.phase,
                state.owner,
                state.ticksInPhase,
                state.missionTicks,
                state.mission.destination,
                bridge.currentTarget(),
                state.route != null ? state.route.primary : null,
                state.lastTransitionReason,
                state.abortReason,
                bridge.isPathing(),
                bridge.isStuck(),
                verifier.lastReport(),
                0.0, 0.0, 0, "n/a", false, "n/a"
        );
    }

    public synchronized TravelPhase currentPhase() { return state.phase; }

    // ──────────────────────────────────────────────────────────────
    // Tick
    // ──────────────────────────────────────────────────────────────

    // Pre-physics tick: drives BounceController before tickMovement().
    public synchronized void preTick(Object client) {
        if (state.phase == TravelPhase.BOUNCING) {
            bounce.preTick();
        } else if (state.phase == TravelPhase.SETTLE) {
            // Hold the travel yaw during the grace window so the player
            // faces correctly when the bounce loop restarts.
            setPlayerYaw(yawForDir(settleYawDx, settleYawDz));
        }
    }

    public synchronized void tick(Object client) {
        if (state.phase == TravelPhase.IDLE) {
            // Count down auto-resume delay and re-plan when it expires.
            if (autoResumeTicks > 0) {
                autoResumeTicks--;
                if (autoResumeTicks == 0 && state.mission != null) {
                    LOGGER.info("[Travel] auto-resuming after abort (attempt {}/{})",
                            autoResumeAttempts, MAX_AUTO_RESUME_ATTEMPTS);
                    TravelMission m = state.mission;
                    state.reset();
                    verifier.clear();
                    state.mission = m;
                    currentLegIndex = -1;
                    detourResumeExit = null;
                    transition(TravelPhase.PLANNING, "auto-resume after abort (attempt " + autoResumeAttempts + ")");
                }
            }
            return;
        }
        state.ticksInPhase++;
        state.missionTicks++;

        driveOwner();
        tickVerifier();

        switch (state.phase) {
            case PLANNING               -> tickPlanning();
            case APPROACH_ONRAMP        -> tickApproach();
            case BOUNCING               -> tickBouncing();
            case MINING_TO_FREENETHER   -> tickMining();
            case ELYTRA_RESUPPLY        -> tickElytraResupply();
            case OFFRAMP_HANDOFF        -> tickOffRampHandoff();
            case ARRIVED, ABORTED       -> tickTerminal();
            case PAUSED                 -> { /* no-op */ }
            case VERIFYING_DETOUR       -> tickVerifyingDetour();
            case DETOURING              -> tickDetouring();
            case SETTLE                 -> tickSettle();
            case LAUNCH                 -> tickLaunch();
            case ELYTRA_CRUISE          -> tickElytraCruise();
            case ELYTRA_FALLBACK        -> tickElytraFallback();
            default -> { /* IDLE handled above */ }
        }
    }

    private void driveOwner() {
        if      (state.owner == MovementOwner.BARITONE) bridge.tick();
        else if (state.owner == MovementOwner.BOUNCE)   bounce.tick();
        else if (state.owner == MovementOwner.FLIGHT)   flight.tick();
    }

    private void tickVerifier() {
        if (state.route == null || state.route.primary == null) return;
        BlockPos pos = currentPlayerPos();
        if (pos == null) return;
        if (state.phase == TravelPhase.APPROACH_ONRAMP
                || state.phase == TravelPhase.MINING_TO_FREENETHER
                || state.phase == TravelPhase.BOUNCING) {
            verifier.tick(pos);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Phase handlers
    // ──────────────────────────────────────────────────────────────

    private void tickPlanning() {
        if (state.route != null) return;

        BlockPos origin = currentPlayerPos();
        if (origin == null) { abort("no player on PLANNING"); return; }

        HighwayPlanner.Options opts = new HighwayPlanner.Options()
                .freeNetherFlightThreshold(state.mission.freeNetherFlightThreshold);
        if (state.mission.expectedHighwayFloorY != Integer.MIN_VALUE)
            opts.expectedFloorY(state.mission.expectedHighwayFloorY);

        Optional<HighwayRoute> planned = planner.plan(origin, state.mission.destination, opts);
        if (planned.isEmpty()) { abort("planner returned no route"); return; }

        state.route = planned.get();
        currentLegIndex = -1;
        verifier.setHighway(state.route.primary, state.route.travelDx, state.route.travelDz);
        LOGGER.info("[Travel] planned {}", state.route);
        advanceLeg("planning complete");
    }

    private void tickApproach() {
        // Fall detection: abort on fall; nether-elytra would hit bedrock ceiling.
        if (state.route != null && state.route.primary != null) {
            BlockPos pos = currentPlayerPos();
            if (pos != null && pos.getY() < state.route.primary.floorY - 2) {
                abort("approach fall — player below highway (y=" + pos.getY() + ")");
                return;
            }
        }
        if (bridge.isArrived()) { advanceLeg("approach arrived"); return; }
        if (bridge.isStuck()) {
            // Wither knockback or similar — abort and let auto-resume retry.
            // Do NOT escalate to nether-elytra on a confined highway.
            abort("approach stuck — wither/knockback? auto-resume will retry");
        }
    }

    private void tickBouncing() {
        // ── Elytra durability check ───────────────────────────────────
        /*? if >=26.1 {*//*
        Minecraft mc = Minecraft.getInstance();
        *//*?} else {*/
        MinecraftClient mc = MinecraftClient.getInstance();
        /*?}*/
        if (mc.player != null && ElytraManager.needsResupply(mc)) {
            startElytraResupply();
            return;
        }

        // ── Stuck / fall — checked before grief so that a fall inside a grief
        //    zone (e.g. wither blast + multi-chunk void) takes the elytra-escape
        //    path rather than starting a ground detour with the player mid-air.
        if (bounce.isStuck()) {
            // No nether-elytra escalation: corridor too confined; auto-resume retries.
            abort(bounce.isStuckFromFall() ? "bounce: fell off highway, gliding to safety"
                                           : "bounce stuck");
            return;
        }

        // ── Grief check: interrupt and plan detour ────────────────
        IntegrityReport rep = verifier.lastReport();
        if (rep.status() == IntegrityReport.Status.GRIEFED
                && rep.confidence() >= DetourPlanner.MIN_CONFIDENCE) {
            if (state.mission == null || !state.mission.allowDetour) {
                abort("highway grief detected and detours disabled: " + rep);
                return;
            }
            LOGGER.warn("[Travel] grief detected during bounce: {}", rep);
            if (state.route != null) {
                for (HighwayRoute.Leg leg : state.route.legs) {
                    if (leg instanceof HighwayRoute.BounceLeg bl) {
                        detourResumeExit = bl.exitColumn();
                        break;
                    }
                }
            }
            releaseOwner(state.owner);
            state.owner = MovementOwner.NONE;
            transition(TravelPhase.VERIFYING_DETOUR, "grief detected: " + rep);
            return;
        }
        // ── Wall / obstacle ahead: short forward bypass ───────────
        if (bounce.isWallAhead()) {
            LOGGER.warn("[Travel] wall/obstacle ahead during bounce, triggering bypass");
            if (state.mission == null || !state.mission.allowDetour) {
                abort("wall ahead and detours disabled");
                return;
            }
            triggerWallBypass();
            return;
        }
        // ── Knockback / lateral displacement ────────────────────
        if (isPlayerKnockedOffHighway()) {
            if (state.mission == null || !state.mission.allowDetour) {
                abort("knocked off highway and detours disabled");
                return;
            }
            triggerKnockbackRecovery();
            return;
        }
        if (bounce.isArrived()) { advanceLeg("bounce arrived"); }
    }

    // Drive ElytraManager; resume bounce on done, abort on failure, IDLE for standalone.
    private void tickElytraResupply() {
        elytra.tick();
        if (elytra.isDone()) {
            LOGGER.info("[Travel] elytra resupply done");
            verifier.resetLastReport();
            if (state.route == null) {
                transition(TravelPhase.IDLE, "standalone repair complete");
            } else if (detourResumeExit != null) {
                acquireOwner(MovementOwner.BOUNCE);
                bounce.start(state.route.primary, detourResumeExit,
                        state.route.travelDx, state.route.travelDz);
                detourResumeExit = null;
                transition(TravelPhase.BOUNCING, "elytra resupply complete, resuming bounce");
            } else {
                advanceLeg("elytra resupply complete");
            }
        } else if (elytra.isFailed()) {
            if (state.route == null) {
                elytra.stop();
                transition(TravelPhase.IDLE, "standalone repair failed");
            } else {
                abort("elytra resupply failed — no viable traveling materials");
            }
        }
    }

    /** Begin elytra resupply: stop bounce, save resume target, launch ElytraManager. */
    private void startElytraResupply() {
        LOGGER.warn("[Travel] elytra low/broken — entering ELYTRA_RESUPPLY");
        if (detourResumeExit == null && state.route != null) {
            for (HighwayRoute.Leg leg : state.route.legs) {
                if (leg instanceof HighwayRoute.BounceLeg bl) {
                    detourResumeExit = bl.exitColumn();
                    break;
                }
            }
        }
        releaseOwner(state.owner);
        state.owner = MovementOwner.NONE;
        elytra.start();
        transition(TravelPhase.ELYTRA_RESUPPLY, "elytra durability critical");
    }

    // Plan detour waypoints and hand off to Baritone.
    private void tickVerifyingDetour() {
        BlockPos pos = currentPlayerPos();
        if (pos == null) { abort("no player pos during detour verification"); return; }

        IntegrityReport rep = verifier.lastReport();
        List<BlockPos> waypoints = DetourPlanner.plan(
                state.route.primary, rep, pos, state.route.travelDx, state.route.travelDz);
        if (waypoints.isEmpty()) {
            abort("detour planning failed: " + rep);
            return;
        }

        releaseOwner(state.owner);
        acquireOwner(MovementOwner.BARITONE);
        bridge.walkToWaypoints(waypoints, 3);
        transition(TravelPhase.DETOURING,
                "detour planned: " + waypoints.size() + " waypoints, griefRange=["
                + rep.griefStartOffset() + "," + rep.griefEndOffset() + "]");
    }

    // Resume bounce when Baritone finishes the detour.
    private void tickDetouring() {
        // Fall detection: abort if below highway floor; auto-resume re-plans.
        if (state.route != null && state.route.primary != null) {
            BlockPos pos = currentPlayerPos();
            if (pos != null && pos.getY() < state.route.primary.floorY - 2) {
                abort("detouring fall — player below highway (y=" + pos.getY() + ")");
                return;
            }
        }
        // If Baritone escalated to elytra during ground detour, follow its lead.
        if (bridge.isElytraOwning()) {
            LOGGER.info("[Travel] DETOURING: Baritone switched to elytra — upgrading to ELYTRA_CRUISE");
            transition(TravelPhase.ELYTRA_CRUISE, "Baritone elytra took over during ground detour");
            return;
        }
        if (bridge.isArrived()) {
            LOGGER.info("[Travel] detour/bypass complete, settling before bounce resume");
            if (detourResumeExit != null && state.route != null) {
                settleYawDx = state.route.travelDx;
                settleYawDz = state.route.travelDz;
                settleTicks = 10;   // 10-tick grace window before bounce resumes
                releaseOwner(state.owner);
                state.owner = MovementOwner.NONE;
                transition(TravelPhase.SETTLE, "detour complete, entering settle");
            } else {
                detourResumeExit = null;
                advanceLeg("detour complete (no resume exit)");
            }
            return;
        }
        if (bridge.isStuck()) {
            abort("detour stuck — wither/knockback? auto-resume will retry");
        }
    }

    // 10-tick yaw-hold after detour/bypass before resuming the bounce.
    private void tickSettle() {
        settleTicks--;
        if (settleTicks <= 0) {
            settleTicks = 0;
            if (detourResumeExit != null && state.route != null) {
                LOGGER.info("[Travel] SETTLE done, resuming bounce to {}", detourResumeExit);
                // Clear stale report without re-arming the scan timer.
                verifier.resetLastReport();
                acquireOwner(MovementOwner.BOUNCE);
                bounce.start(state.route.primary, detourResumeExit,
                        state.route.travelDx, state.route.travelDz);
                detourResumeExit = null;
                autoResumeAttempts = 0; // detour succeeded: reset retry budget
                transition(TravelPhase.BOUNCING, "settle complete, resuming bounce");
            } else {
                detourResumeExit = null;
                advanceLeg("settle complete (no resume exit)");
            }
        }
    }

    private void tickMining() {
        if (bridge.isArrived()) {
            IntegrityReport rep = verifier.lastReport();
            LOGGER.info("[Travel] mining arrived; last integrity={}", rep);
            advanceLeg("mining-leg arrived");
            return;
        }
        if (bridge.isStuck()) { abort("mining stuck"); }
    }

    private void tickOffRampHandoff() {
        advanceLeg("handoff complete");
    }

    private void tickTerminal() {
        if (state.ticksInPhase >= 1) {
            TravelPhase from = state.phase;
            String savedAbortReason = state.abortReason;
            releaseOwner(state.owner);
            verifier.clear();
            // Keep mission so resume() can restart after stop.
            TravelMission lastMission = state.mission;
            state.reset();
            state.mission = lastMission;
            currentLegIndex = -1;
            detourResumeExit = null;
            TravelLog.get().recordTransition(0, 0, from, TravelPhase.IDLE, "terminal cleanup");

            // Schedule automatic re-plan for non-user aborts when the mission allows it.
            if (from == TravelPhase.ABORTED
                    && !"user stop".equals(savedAbortReason)
                    && lastMission != null && lastMission.autoResume
                    && autoResumeAttempts < MAX_AUTO_RESUME_ATTEMPTS) {
                autoResumeAttempts++;
                autoResumeTicks = AUTO_RESUME_DELAY_TICKS;
                LOGGER.warn("[Travel] auto-resume scheduled in {}t (attempt {}/{}) reason={}",
                        AUTO_RESUME_DELAY_TICKS, autoResumeAttempts,
                        MAX_AUTO_RESUME_ATTEMPTS, savedAbortReason);
            }
        }
    }

    // Poll until Baritone elytra takes ownership; re-issue command each tick. Abort on timeout.
    private void tickLaunch() {
        if (bridge.isElytraOwning()) {
            LOGGER.info("[Travel] LAUNCH -> ELYTRA_CRUISE (Baritone elytra active, t={})", state.ticksInPhase);
            transition(TravelPhase.ELYTRA_CRUISE, "Baritone elytra started");
            return;
        }
        // Re-issue pathTo every tick until Baritone acknowledges ownership.
        BlockPos dest = flightDestination();
        if (dest == null && state.mission != null) dest = state.mission.destination;
        if (bridge.isAvailable() && dest != null) bridge.startElytraFlight(dest);

        if (state.ticksInPhase > 200) {
            abort("Baritone elytra did not start within 200 ticks");
        }
    }

    // Baritone nether-elytra owns movement; re-request on ownership loss.
    private void tickElytraCruise() {
        if (bridge.isElytraArrived()) {
            transition(TravelPhase.ARRIVED, "elytra cruise arrived");
            return;
        }
        if (bridge.isElytraStuck() || !bridge.isElytraOwning()) {
            LOGGER.warn("[Travel] ELYTRA_CRUISE: Baritone elytra lost ownership — re-requesting");
            BlockPos dest = flightDestination();
            if (dest == null && state.mission != null) dest = state.mission.destination;
            if (dest != null) {
                bridge.startElytraFlight(dest);
                transition(TravelPhase.LAUNCH, "Baritone elytra re-requested after loss");
            } else {
                abort("Baritone elytra lost and no flight destination");
            }
        }
    }

    // FlightController removed — this state should no longer be reached.
    private void tickElytraFallback() {
        abort("ELYTRA_FALLBACK reached — FlightController has been replaced by Baritone nether-elytra");
    }

    // FlightLeg destination from the current route, or null.
    private BlockPos flightDestination() {
        if (state.route == null) return null;
        for (HighwayRoute.Leg leg : state.route.legs) {
            if (leg instanceof HighwayRoute.FlightLeg fl) return fl.destination();
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────────
    // Leg advancement
    // ──────────────────────────────────────────────────────────────

    private void advanceLeg(String reason) {
        if (state.route == null) { abort("advanceLeg with null route"); return; }
        currentLegIndex++;
        if (currentLegIndex >= state.route.legs.size()) {
            transition(TravelPhase.ARRIVED, "all legs complete: " + reason);
            return;
        }
        HighwayRoute.Leg leg = state.route.legs.get(currentLegIndex);

        if (leg instanceof HighwayRoute.ApproachLeg approach) {
            startBaritoneWalk(approach.onRamp(), 2, TravelPhase.APPROACH_ONRAMP, reason);
        } else if (leg instanceof HighwayRoute.BounceLeg bounceLeg) {
            acquireOwner(MovementOwner.BOUNCE);
            bounce.start(bounceLeg.highway(), bounceLeg.exitColumn(),
                    bounceLeg.travelDx(), bounceLeg.travelDz());
            transition(TravelPhase.BOUNCING, reason + " -> bouncing to " + bounceLeg.exitColumn().toShortString());
        } else if (leg instanceof HighwayRoute.OffRampLeg) {
            transition(TravelPhase.OFFRAMP_HANDOFF, "offramp leg");
        } else if (leg instanceof HighwayRoute.MineLeg mine) {
            startBaritoneWalk(mine.freeNetherTarget(), 1, TravelPhase.MINING_TO_FREENETHER, reason);
        } else if (leg instanceof HighwayRoute.FlightLeg flightLeg) {
            if (state.mission != null && state.mission.useElytra) {
                acquireOwner(MovementOwner.BARITONE);
                bridge.startElytraFlight(flightLeg.destination());
                transition(TravelPhase.LAUNCH, reason + " -> launching to " + flightLeg.destination().toShortString());
            } else {
                LOGGER.info("[Travel] FlightLeg skipped (useElytra=false)");
                transition(TravelPhase.ARRIVED, "flight leg skipped (useElytra disabled)");
            }
        }
    }

    private void startBaritoneWalk(BlockPos target, int radius, TravelPhase phase, String reason) {
        acquireOwner(MovementOwner.BARITONE);
        bridge.walkNear(target, radius);
        transition(phase, reason + " -> walk to " + target.toShortString());
    }

    private static final int WALL_BYPASS_DISTANCE      = 12; // blocks to walk past a wall
    private static final int KNOCKBACK_PERP_THRESHOLD  =  5; // off-axis blocks before recovery

    private void triggerWallBypass() {
        BlockPos pos = currentPlayerPos();
        if (pos == null || state.route == null) {
            abort("no player pos for wall bypass");
            return;
        }
        // Capture the bounce leg exit if not already set.
        if (detourResumeExit == null) {
            for (HighwayRoute.Leg leg : state.route.legs) {
                if (leg instanceof HighwayRoute.BounceLeg bl) {
                    detourResumeExit = bl.exitColumn();
                    break;
                }
            }
        }
        BlockPos goal = new BlockPos(
                pos.getX() + state.route.travelDx * WALL_BYPASS_DISTANCE,
                pos.getY(),
                pos.getZ() + state.route.travelDz * WALL_BYPASS_DISTANCE);
        releaseOwner(state.owner);
        acquireOwner(MovementOwner.BARITONE);
        bridge.walkNear(goal, 2);
        transition(TravelPhase.DETOURING, "wall bypass: goal=" + goal.toShortString());
    }

    // True when the player's perp offset from the highway axis exceeds KNOCKBACK_PERP_THRESHOLD.
    private boolean isPlayerKnockedOffHighway() {
        if (state.route == null || state.route.primary == null) return false;
        BlockPos pos = currentPlayerPos();
        if (pos == null) return false;
        HighwayCandidate hw = state.route.primary;
        if (hw.entry == null) return false;
        int perpDx = hw.axis.perpDx();
        int perpDz = hw.axis.perpDz();
        int perpSq = perpDx * perpDx + perpDz * perpDz; // 1 cardinal, 2 diagonal
        // Avoid floating-point division: scale threshold by perpSq instead.
        int dot = (pos.getX() - hw.entry.getX()) * perpDx
                + (pos.getZ() - hw.entry.getZ()) * perpDz;
        return Math.abs(dot) > KNOCKBACK_PERP_THRESHOLD * perpSq;
    }

    // Project player onto travel axis and walk back; reuses DETOURING → SETTLE → BOUNCING.
    private void triggerKnockbackRecovery() {
        BlockPos pos = currentPlayerPos();
        if (pos == null || state.route == null) { abort("no player pos for knockback recovery"); return; }
        HighwayCandidate hw = state.route.primary;
        if (detourResumeExit == null) {
            for (HighwayRoute.Leg leg : state.route.legs) {
                if (leg instanceof HighwayRoute.BounceLeg bl) {
                    detourResumeExit = bl.exitColumn();
                    break;
                }
            }
        }
        // Project player onto travel axis from the highway entry point.
        int ex = hw.entry.getX(), ez = hw.entry.getZ();
        int dx = state.route.travelDx, dz = state.route.travelDz;
        int dSq = dx * dx + dz * dz;
        int t   = ((pos.getX() - ex) * dx + (pos.getZ() - ez) * dz) / dSq;
        BlockPos onHighway = new BlockPos(ex + dx * t, hw.floorY + 1, ez + dz * t);
        LOGGER.warn("[Travel] knocked off highway — walking back to axis at {}", onHighway.toShortString());
        releaseOwner(state.owner);
        acquireOwner(MovementOwner.BARITONE);
        bridge.walkNear(onHighway, 2);
        transition(TravelPhase.DETOURING, "knockback recovery to " + onHighway.toShortString());
    }

    private void abort(String reason) {
        state.abortReason = reason;
        transition(TravelPhase.ABORTED, reason);
    }

    // ──────────────────────────────────────────────────────────────
    // Ownership
    // ──────────────────────────────────────────────────────────────

    private void acquireOwner(MovementOwner next) {
        if (state.owner == next) return;
        releaseOwner(state.owner);
        state.owner = next;
    }

    private void releaseOwner(MovementOwner cur) {
        switch (cur) {
            case BARITONE        -> bridge.cancelAll();
            case BOUNCE          -> bounce.stop();
            case FLIGHT          -> flight.stop();
            case NONE            -> { /* nothing */ }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Transition
    // ──────────────────────────────────────────────────────────────

    private void transition(TravelPhase next, String reason) {
        TravelPhase from = state.phase;
        if (from == next) return;
        state.phase = next;
        state.ticksInPhase = 0;
        state.lastTransitionReason = reason;
        TravelLog.get().recordTransition(missionId(), state.missionTicks, from, next, reason);
        LOGGER.info("[Travel] {} -> {} ({})", from, next, reason);
        if (next.isTerminal()) {
            releaseOwner(state.owner);
            state.owner = MovementOwner.NONE;
        }
    }

    private long missionId() { return state.mission != null ? state.mission.id : 0L; }

    // ──────────────────────────────────────────────────────────────
    // Stonecutter-quarantined helpers
    // ──────────────────────────────────────────────────────────────

    private static BlockPos currentPlayerPos() {
        /*? if >=26.1 {*//*
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        return mc.player.blockPosition();
        *//*?} else {*/
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return null;
        return mc.player.getBlockPos();
        /*?}*/
    }

    // MC yaw in degrees for a travel direction vector.
    private static float yawForDir(int dx, int dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    // Set the player's view yaw (Stonecutter-safe).
    private static void setPlayerYaw(float yaw) {
        /*? if >=26.1 {*//*
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.setYRot(yaw);
        *//*?} else {*/
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.setYaw(yaw);
        /*?}*/
    }
}
