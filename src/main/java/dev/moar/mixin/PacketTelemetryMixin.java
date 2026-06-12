package dev.moar.mixin;

import dev.moar.util.PacketTelemetry;
/*? if >=26.1 {*//*
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
*//*?} else {*/
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
/*?}*/
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Records outgoing packets while /moar packetlog is enabled.
/*? if >=26.1 {*//*
@Mixin(Connection.class)
*//*?} else {*/
@Mixin(ClientConnection.class)
/*?}*/
public abstract class PacketTelemetryMixin {

    /*? if >=26.1 {*//*
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), require = 0)
    private void moar$recordOutgoingPacket(Packet<?> packet, CallbackInfo ci) {
        PacketTelemetry.recordOutgoing(packet);
    }
    *//*?} else {*/
    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"), require = 0)
    private void moar$recordOutgoingPacket(Packet<?> packet, CallbackInfo ci) {
        PacketTelemetry.recordOutgoing(packet);
    }
    /*?}*/
}
