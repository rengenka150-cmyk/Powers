package com.example;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceLocation;
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
    private static final int GRAPPLE_COOLDOWN = 12;
    private static final int DASH_COOLDOWN = 20;

    public record HighJumpPayload() implements CustomPacketPayload {
        public static final Type<HighJumpPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "high_jump")
        );
        public static final StreamCodec<FriendlyByteBuf, HighJumpPayload> CODEC =
                StreamCodec.unit(new HighJumpPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record DashPayload() implements CustomPacketPayload {
        public static final Type<DashPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "dash")
        );
        public static final StreamCodec<FriendlyByteBuf, DashPayload> CODEC =
                StreamCodec.unit(new DashPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private GrappleHookHandler() {}

    public static void register() {
        PayloadTypeRegistry.playC2S().register(HighJumpPayload.TYPE, HighJumpPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(DashPayload.TYPE, DashPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(HighJumpPayload.TYPE, (payload, context) ->
                context.server().execute(() -> performHighJump(context.player())));

        ServerPlayNetworking.registerGlobalReceiver(DashPayload.TYPE, (payload, context) ->
                context.server().execute(() -> performDashOrSlide(context.player())));

        // ЛКМ деревянной киркой по блоку -> крюк-кошка
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (!stack.is(Items.WOODEN_PICKAXE)) {
                return InteractionResult.PASS;
            }

            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }

            if (serverPlayer.getCooldowns().isOnCooldown(stack)) {
                return InteractionResult.FAIL;
            }

            HitResult hit = serverPlayer.pick(MAX_RANGE, 0.0F, false);
            Vec3 eyePos = serverPlayer.getEyePosition();
            Vec3 targetPos = (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit)
                    ? blockHit.getLocation()
                    : Vec3.atCenterOf(pos);

            Vec3 delta = targetPos.subtract(eyePos);
            double distance = delta.length();
            if (distance < 1.2) {
                return InteractionResult.FAIL;
            }

            Vec3 dir = delta.normalize();
            double speed = Math.min(PULL_SPEED + distance * 0.025, 2.35);
            Vec3 velocity = new Vec3(
                    dir.x * speed,
                    Math.max(dir.y * speed + 0.30, 0.38),
                    dir.z * speed
            );

            applyVelocity(serverPlayer, velocity);
            serverPlayer.getCooldowns().addCooldown(stack, GRAPPLE_COOLDOWN);

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

            return InteractionResult.FAIL;
        });

        // ПКМ деревянной киркой -> дэш / скольжение
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (!stack.is(Items.WOODEN_PICKAXE)) {
                return InteractionResult.PASS;
            }

            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }

            if (serverPlayer.getCooldowns().isOnCooldown(stack)) {
                return InteractionResult.FAIL;
            }

            performDashOrSlide(serverPlayer);
            serverPlayer.getCooldowns().addCooldown(stack, DASH_COOLDOWN);
            return InteractionResult.SUCCESS;
        });

        // Команды /dash и /highjump
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerCommands(dispatcher));
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("dash").executes(ctx -> {
            if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
                performDashOrSlide(player);
                return 1;
            }
            return 0;
        }));

        dispatcher.register(Commands.literal("highjump").executes(ctx -> {
            if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
                performHighJump(player);
                return 1;
            }
            return 0;
        }));
    }

    private static void performDashOrSlide(ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0, look.z);
        if (horizontal.lengthSqr() < 1.0E-4) {
            horizontal = new Vec3(0.0, 0.0, 1.0);
        } else {
            horizontal = horizontal.normalize();
        }

        boolean sliding = player.isShiftKeyDown() || player.onGround();
        double forwardSpeed = sliding ? 1.75 : 1.55;
        double upSpeed = sliding ? 0.14 : 0.34;

        Vec3 velocity = new Vec3(horizontal.x * forwardSpeed, upSpeed, horizontal.z * forwardSpeed);
        applyVelocity(player, velocity);

        if (player.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(
                    ParticleTypes.CLOUD,
                    player.getX(),
                    player.getY() + 0.2,
                    player.getZ(),
                    10,
                    0.25,
                    0.1,
                    0.25,
                    0.04
            );
            serverLevel.playSound(
                    null,
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    SoundEvents.PLAYER_ATTACK_SWEEP,
                    SoundSource.PLAYERS,
                    0.9F,
                    1.2F
            );
        }
    }

    private static void performHighJump(ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0, look.z);
        if (horizontal.lengthSqr() > 1.0E-4) {
            horizontal = horizontal.normalize().scale(0.45);
        }

        Vec3 velocity = new Vec3(horizontal.x, 1.28, horizontal.z);
        applyVelocity(player, velocity);

        if (player.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(
                    ParticleTypes.FIREWORK,
                    player.getX(),
                    player.getY() + 0.1,
                    player.getZ(),
                    14,
                    0.25,
                    0.15,
                    0.25,
                    0.05
            );
            serverLevel.playSound(
                    null,
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    SoundEvents.SLIME_JUMP,
                    SoundSource.PLAYERS,
                    1.0F,
                    1.15F
            );
        }
    }

    private static void applyVelocity(ServerPlayer player, Vec3 velocity) {
        player.setDeltaMovement(velocity);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        player.resetFallDistance();
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
