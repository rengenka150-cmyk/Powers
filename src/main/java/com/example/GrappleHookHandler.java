package com.example;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class GrappleHookHandler {
    private static final double MAX_RANGE = 32.0;
    private static final double PULL_SPEED = 1.65;
    private static final int COOLDOWN_TICKS = 15; // 0.75 сек

    private GrappleHookHandler() {}

    public static void register() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (!stack.is(Items.FISHING_ROD)) {
                return InteractionResult.PASS;
            }

            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }

            if (serverPlayer.getCooldowns().isOnCooldown(stack)) {
                return InteractionResult.FAIL;
            }

            HitResult hit = serverPlayer.pick(MAX_RANGE, 0.0F, false);
            if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit)) {
                return InteractionResult.PASS;
            }

            Vec3 eyePos = serverPlayer.getEyePosition();
            Vec3 targetPos = blockHit.getLocation();
            Vec3 delta = targetPos.subtract(eyePos);
            double distance = delta.length();

            if (distance < 1.5) {
                return InteractionResult.PASS;
            }

            Vec3 direction = delta.normalize();
            double speed = Math.min(PULL_SPEED + distance * 0.025, 2.35);
            Vec3 velocity = new Vec3(
                    direction.x * speed,
                    Math.max(direction.y * speed + 0.32, 0.42),
                    direction.z * speed
            );

            serverPlayer.setDeltaMovement(velocity);
            serverPlayer.hasImpulse = true;
            serverPlayer.resetFallDistance();
            serverPlayer.getCooldowns().addCooldown(stack, COOLDOWN_TICKS);

            if (level instanceof ServerLevel serverLevel) {
                spawnRopeParticles(serverLevel, eyePos, targetPos);
                serverLevel.playSound(
                        null,
                        serverPlayer.getX(),
                        serverPlayer.getY(),
                        serverPlayer.getZ(),
                        SoundEvents.FISHING_BOBBER_THROW,
                        SoundSource.PLAYERS,
                        1.0F,
                        1.35F
                );
            }

            return InteractionResult.SUCCESS;
        });
    }

    private static void spawnRopeParticles(ServerLevel level, Vec3 start, Vec3 end) {
        Vec3 diff = end.subtract(start);
        double length = diff.length();
        int steps = Math.max(4, (int) (length * 3));

        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            Vec3 point = start.add(diff.scale(t));
            level.sendParticles(
                    ParticleTypes.CRIT,
                    point.x,
                    point.y,
                    point.z,
                    1,
                    0.0,
                    0.0,
                    0.0,
                    0.0
            );
        }
    }
}
