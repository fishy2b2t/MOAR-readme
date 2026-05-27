package dev.moar.mixin;

import dev.moar.travel.bounce.BounceController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Spoofs isGliding/isFallFlying to true for the local player while BounceController is active.
 *
 * Without this, walking physics apply client-side while the server uses elytra physics,
 * causing position divergence and rubber-band every tick.
 *
 * Also zeroes jumpingCooldown each tick so jump() fires on every ground contact.
 *
 * Method name varies by version: isFallFlying (1.21.1 Yarn, Mojmap 26.1),
 * isGliding (1.21.4-1.21.11 Yarn).
 */
/*? if >=26.1 {*//*
@Mixin(net.minecraft.world.entity.LivingEntity.class)
*//*?} else {*/
@Mixin(net.minecraft.entity.LivingEntity.class)
/*?}*/
public abstract class BounceGlideSpoofLivingEntityMixin {

    /*? if >=26.1 {*//*
    @Shadow private int noJumpDelay;
    *//*?} else {*/
    @Shadow protected int jumpingCooldown;
    /*?}*/

    // ── isGliding / isFallFlying spoof ───────────────────────────────

    /*? if >=26.1 {*//*
    @Inject(method = "isFallFlying", at = @At("HEAD"), cancellable = true)
    private void moar$ebounceSpoofGliding(CallbackInfoReturnable<Boolean> cir) {
        if (!(((Object) this) instanceof net.minecraft.client.player.LocalPlayer)) return;
        net.minecraft.client.player.LocalPlayer p = net.minecraft.client.Minecraft.getInstance().player;
        if (p != (Object) this) return;
        if (!BounceController.get().isActive()) return;
        cir.setReturnValue(true);
    }
    *//*?} else if >=1.21.4 {*//*
    @Inject(method = "isGliding", at = @At("HEAD"), cancellable = true)
    private void moar$ebounceSpoofGliding(CallbackInfoReturnable<Boolean> cir) {
        if (!(((Object) this) instanceof net.minecraft.client.network.ClientPlayerEntity)) return;
        net.minecraft.client.network.ClientPlayerEntity p = net.minecraft.client.MinecraftClient.getInstance().player;
        if (p != (Object) this) return;
        if (!BounceController.get().isActive()) return;
        cir.setReturnValue(true);
    }
    *//*?} else {*/
    @Inject(method = "isFallFlying", at = @At("HEAD"), cancellable = true)
    private void moar$ebounceSpoofGliding(CallbackInfoReturnable<Boolean> cir) {
        if (!(((Object) this) instanceof net.minecraft.client.network.ClientPlayerEntity)) return;
        net.minecraft.client.network.ClientPlayerEntity p = net.minecraft.client.MinecraftClient.getInstance().player;
        if (p != (Object) this) return;
        if (!BounceController.get().isActive()) return;
        cir.setReturnValue(true);
    }
    /*?}*/

    // ── jumpingCooldown reset ────────────────────────────────────────

    /*? if >=26.1 {*//*
    @Inject(method = "aiStep", at = @At("HEAD"))
    private void moar$ebounceResetJumpCooldown(CallbackInfo ci) {
        if (!(((Object) this) instanceof net.minecraft.client.player.LocalPlayer)) return;
        if (net.minecraft.client.Minecraft.getInstance().player != (Object) this) return;
        if (!BounceController.get().isActive()) return;
        this.noJumpDelay = 0;
    }
    *//*?} else {*/
    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void moar$ebounceResetJumpCooldown(CallbackInfo ci) {
        if (!(((Object) this) instanceof net.minecraft.client.network.ClientPlayerEntity)) return;
        if (net.minecraft.client.MinecraftClient.getInstance().player != (Object) this) return;
        if (!BounceController.get().isActive()) return;
        this.jumpingCooldown = 0;
    }
    /*?}*/
}
