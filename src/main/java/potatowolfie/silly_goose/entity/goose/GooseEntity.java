package potatowolfie.silly_goose.entity.goose;

import net.minecraft.component.ComponentType;
import net.minecraft.component.ComponentsAccess;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.spawn.SpawnContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.LazyRegistryEntryReference;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import potatowolfie.silly_goose.advancement.UnfairTradeAdvancementHandler;
import potatowolfie.silly_goose.entity.SillyGooseEntities;
import potatowolfie.silly_goose.entity.goose.goals.*;
import potatowolfie.silly_goose.entity.goose.variant.GooseVariant;
import potatowolfie.silly_goose.entity.goose.variant.GooseVariants;
import potatowolfie.silly_goose.item.SillyGooseItems;
import potatowolfie.silly_goose.registry.SillyGooseDataComponentTypes;
import potatowolfie.silly_goose.registry.SillyGooseRegistryKeys;
import potatowolfie.silly_goose.registry.SillyGooseTrackedDataHandlerRegistry;
import potatowolfie.silly_goose.sound.SillyGooseSounds;

import java.util.List;
import java.util.UUID;

public class GooseEntity extends AnimalEntity {
    private static final TrackedData<RegistryEntry<GooseVariant>> VARIANT;
    private static final TrackedData<Boolean> PREFERS_WATER;
    private static final TrackedData<Boolean> CAN_PICKUP_LOOT;
    private static final TrackedData<Boolean> IS_IN_HIT_AND_RUN_MODE;

    public final AnimationState idleAnimationState = new AnimationState();
    public final AnimationState idleWaterAnimationState = new AnimationState();
    public final AnimationState wingsUpIdleAnimationState = new AnimationState();
    public final AnimationState walkAnimationState = new AnimationState();
    public final AnimationState runAnimationState = new AnimationState();
    public final AnimationState swimAnimationState = new AnimationState();
    public final AnimationState swimFastAnimationState = new AnimationState();

    private int idleAnimationTimeout = 0;
    private boolean isIdleAnimationRunning = false;
    private boolean isWalkingAnimationRunning = false;
    private boolean animationStartedThisTick = false;

    private int preferenceChangeTimer = 0;
    private static final int MIN_PREFERENCE_CHANGE_TIME = 4800;
    private static final int MAX_PREFERENCE_CHANGE_TIME = 7200;

    private static final float CHANCE_OF_PICK_UP_WEAPON = 1.0F;
    private int swordPickupCooldownTimer = 0;

    private int offPreferenceTimer = 0;
    private boolean isInOffPreferenceMode = false;
    private static final int MIN_OFF_PREFERENCE_TIME = 600;
    private static final int MAX_OFF_PREFERENCE_TIME = 1200;

    private int eggLayTimer = 0;
    private static final int MIN_EGG_LAY_TIME = 6000;
    private static final int MAX_EGG_LAY_TIME = 12000;

    private int babyPanicTimer = 0;
    private static final int BABY_PANIC_DURATION = 100;
    private static final int BABY_PANIC_DURATION_VARIANCE = 40;
    private static final double BABY_PANIC_SPEED_MULTIPLIER = 1.5;

    @Nullable
    private UUID revengeTargetUUID = null;
    private int revengeTimer = 0;
    private long revengeExpirationTime = 0;
    private static final int MIN_REVENGE_TIMER = 1200;
    private static final int MAX_REVENGE_TIMER = 2400;
    private static final long MIN_REVENGE_DURATION_MS = 2 * 60 * 60 * 1000L;
    private static final long MAX_REVENGE_DURATION_MS = 200 * 60 * 1000L;
    private static final double REVENGE_NOTIFICATION_RADIUS = 64.0;

    public GooseEntity(EntityType<? extends AnimalEntity> entityType, World world) {
        super(entityType, world);
        this.setPathfindingPenalty(PathNodeType.WATER, 0.0F);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new GooseRevengeGoal(this));
        this.goalSelector.add(1, new GooseHitAndRunGoal(this));
        this.goalSelector.add(2, new GooseStealFromVillagerGoal(this));
        this.goalSelector.add(3, new EscapeDangerGoal(this, 1.4));
        this.goalSelector.add(4, new AnimalMateGoal(this, 1.0));
        this.goalSelector.add(5, new TemptGoal(this, 1.1, stack -> stack.isIn(ItemTags.CHICKEN_FOOD), false));
        this.goalSelector.add(6, new BabyGooseFollowGoal(this, 1.1));
        this.goalSelector.add(7, new GooseWanderGoal(this, 1.0));
        this.goalSelector.add(8, new LookAtEntityGoal(this, PlayerEntity.class, 6.0F));
        this.goalSelector.add(9, new LookAroundGoal(this));
    }

    public static DefaultAttributeContainer.Builder createGooseAttributes() {
        return AnimalEntity.createMobAttributes()
                .add(EntityAttributes.MAX_HEALTH, 10.0)
                .add(EntityAttributes.MOVEMENT_SPEED, 0.25)
                .add(EntityAttributes.WATER_MOVEMENT_EFFICIENCY, 1.0)
                .add(EntityAttributes.ATTACK_DAMAGE, 3.0)
                .add(EntityAttributes.JUMP_STRENGTH, 0.42)
                .add(EntityAttributes.TEMPT_RANGE, 10.0);
    }

    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(VARIANT, Variants.getOrDefaultOrThrow(this.getRegistryManager(), GooseVariants.TEMPERATE));
        builder.add(PREFERS_WATER, this.random.nextBoolean());
        builder.add(CAN_PICKUP_LOOT, false);
        builder.add(IS_IN_HIT_AND_RUN_MODE, false);
    }

    protected void writeCustomData(WriteView view) {
        super.writeCustomData(view);
        Variants.writeData(view, this.getVariant());
        view.putBoolean("PrefersWater", this.getPrefersWater());
        view.putBoolean("CanPickUpLoot", this.canPickUpLoot());
        view.putInt("PreferenceChangeTimer", this.preferenceChangeTimer);
        view.putInt("OffPreferenceTimer", this.offPreferenceTimer);
        view.putBoolean("IsInOffPreferenceMode", this.isInOffPreferenceMode);
        view.putInt("EggLayTimer", this.eggLayTimer);
        view.putInt("SwordPickupCooldownTimer", this.swordPickupCooldownTimer);
        view.putInt("BabyPanicTimer", this.babyPanicTimer);

        if (this.revengeTargetUUID != null) {
            view.put("RevengeTargetUUID", Uuids.CODEC, this.revengeTargetUUID);
        }
        view.putInt("RevengeTimer", this.revengeTimer);
        view.putLong("RevengeExpirationTime", this.revengeExpirationTime);
    }

    protected void readCustomData(ReadView view) {
        super.readCustomData(view);
        if (!spawnedFromEgg) {
            Variants.fromData(view, SillyGooseRegistryKeys.GOOSE_VARIANT).ifPresent(this::setVariant);
        }

        boolean savedPreference = view.getBoolean("PrefersWater", this.random.nextBoolean());
        this.setPrefersWater(savedPreference);

        boolean canPickup = view.getBoolean("CanPickUpLoot", false);
        this.setCanPickUpLoot(canPickup);
        this.preferenceChangeTimer = view.getInt("PreferenceChangeTimer", 0);
        this.offPreferenceTimer = view.getInt("OffPreferenceTimer", 0);
        this.isInOffPreferenceMode = view.getBoolean("IsInOffPreferenceMode", false);
        this.eggLayTimer = view.getInt("EggLayTimer", 0);
        this.swordPickupCooldownTimer = view.getInt("SwordPickupCooldownTimer", 0);
        this.babyPanicTimer = view.getInt("BabyPanicTimer", 0);

        view.read("RevengeTargetUUID", Uuids.CODEC).ifPresent(uuid -> this.revengeTargetUUID = uuid);
        this.revengeTimer = view.getInt("RevengeTimer", 0);
        this.revengeExpirationTime = view.getLong("RevengeExpirationTime", 0L);
    }

    private boolean spawnedFromEgg = false;
    public void setSpawnedFromEgg(boolean spawnedFromEgg) {
        this.spawnedFromEgg = spawnedFromEgg;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.getEntityWorld().isClient()) {
            setupAnimationStates();
        } else {
            handleWaterFloating();
            handlePreferenceChange();
            handleOffPreferenceTimer();
            handleEggLaying();
            handleRevengeTimer();
            handleSwordPickupCooldown();
            handleBabyPanic();
        }
    }

    private void handleRevengeTimer() {
        if (revengeTargetUUID == null) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (revengeExpirationTime > 0 && currentTime >= revengeExpirationTime) {
            revengeTargetUUID = null;
            revengeTimer = 0;
            revengeExpirationTime = 0;
            return;
        }

        PlayerEntity targetPlayer = getRevengeTargetPlayer();
        if (targetPlayer != null) {
            double distance = this.squaredDistanceTo(targetPlayer);

            if (distance <= 32.0 * 32.0) {
                if (revengeTimer > 0) {
                    revengeTimer--;
                }
            }
        }
    }

    private void handleBabyPanic() {
        if (this.babyPanicTimer > 0) {
            this.babyPanicTimer--;
        }
    }

    public boolean isInPanicMode() {
        return this.isBaby() && this.babyPanicTimer > 0;
    }

    @Override
    public float getMovementSpeed() {
        float baseSpeed = super.getMovementSpeed();
        if (isInPanicMode()) {
            return (float)(baseSpeed * BABY_PANIC_SPEED_MULTIPLIER);
        }
        return baseSpeed;
    }

    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        boolean damaged = super.damage(world, source, amount);

        if (!damaged) {
            return false;
        }

        Entity attacker = source.getAttacker();
        if (this.isBaby() && attacker instanceof PlayerEntity player) {
            this.getNavigation().stop();
            this.getJumpControl().setActive();
            this.getLookControl().lookAt(player, 180.0F, 180.0F);
            this.getMoveControl().moveTo(
                    this.getX() + (this.random.nextDouble() - 0.5) * 4.0,
                    this.getY(),
                    this.getZ() + (this.random.nextDouble() - 0.5) * 4.0,
                    1.6
            );

            this.babyPanicTimer = BABY_PANIC_DURATION +
                    this.random.nextInt(BABY_PANIC_DURATION_VARIANCE);

            alertAdultsOfBabyAttack(player);
        }
        if (damaged && this.isDead() && attacker instanceof PlayerEntity player) {
            notifyNearbyGeese(player);
        }

        return damaged;
    }

    private void alertAdultsOfBabyAttack(PlayerEntity attacker) {
        Box box = this.getBoundingBox().expand(REVENGE_NOTIFICATION_RADIUS);

        List<GooseEntity> adults = this.getEntityWorld().getEntitiesByClass(
                GooseEntity.class,
                box,
                goose -> goose != this && goose.isAlive() && !goose.isBaby()
        );

        for (GooseEntity adult : adults) {
            adult.setRevengeTarget(attacker);
        }
    }

    private void notifyNearbyGeese(PlayerEntity killer) {
        Box searchBox = this.getBoundingBox().expand(REVENGE_NOTIFICATION_RADIUS);
        List<GooseEntity> nearbyGeese = this.getEntityWorld().getEntitiesByClass(
                GooseEntity.class,
                searchBox,
                g -> g != this && g.isAlive()
        );

        for (GooseEntity goose : nearbyGeese) {
            goose.setRevengeTarget(killer);
        }
    }

    public void setRevengeTarget(PlayerEntity player) {
        this.revengeTargetUUID = player.getUuid();
        this.revengeTimer = MIN_REVENGE_TIMER + this.random.nextInt(MAX_REVENGE_TIMER - MIN_REVENGE_TIMER);

        long durationMs = MIN_REVENGE_DURATION_MS +
                (long)(this.random.nextDouble() * (MAX_REVENGE_DURATION_MS - MIN_REVENGE_DURATION_MS));
        this.revengeExpirationTime = System.currentTimeMillis() + durationMs;
    }

    public boolean hasRevengeTarget() {
        if (revengeTargetUUID == null) {
            return false;
        }

        long currentTime = System.currentTimeMillis();
        if (revengeExpirationTime > 0 && currentTime >= revengeExpirationTime) {
            return false;
        }

        return true;
    }

    public boolean isRevengeTimerReady() {
        return revengeTimer <= 0;
    }

    public void resetRevengeTimer() {
        this.revengeTimer = MIN_REVENGE_TIMER + this.random.nextInt(MAX_REVENGE_TIMER - MIN_REVENGE_TIMER);
    }

    @Nullable
    public PlayerEntity getRevengeTargetPlayer() {
        if (revengeTargetUUID == null) {
            return null;
        }

        if (this.getEntityWorld() instanceof ServerWorld serverWorld) {
            return serverWorld.getPlayerByUuid(revengeTargetUUID);
        }

        return null;
    }

    private void handleEggLaying() {
        if (this.isBaby()) {
            return;
        }

        if (this.eggLayTimer <= 0) {
            this.layEgg();

            this.eggLayTimer = MIN_EGG_LAY_TIME +
                    this.random.nextInt(MAX_EGG_LAY_TIME - MIN_EGG_LAY_TIME);
        } else {
            this.eggLayTimer--;
        }
    }

    private void layEgg() {
        ItemStack eggStack = getEggForVariant();

        if (!eggStack.isEmpty()) {
            ItemEntity eggEntity = new ItemEntity(
                    this.getEntityWorld(),
                    this.getX(),
                    this.getY(),
                    this.getZ(),
                    eggStack
            );

            eggEntity.setVelocity(0, 0.05, 0);

            this.getEntityWorld().spawnEntity(eggEntity);

            this.playSound(SoundEvents.ENTITY_CHICKEN_EGG, 0.5F, (this.random.nextFloat() - this.random.nextFloat()) * 0.2F + 1.0F);
        }
    }

    private ItemStack getEggForVariant() {
        RegistryEntry<GooseVariant> variant = this.getVariant();

        if (variant.matchesKey(GooseVariants.TEMPERATE)) {
            return new ItemStack(SillyGooseItems.WHITE_EGG);
        } else if (variant.matchesKey(GooseVariants.COLD)) {
            return new ItemStack(SillyGooseItems.BIG_WHITE_EGG);
        } else if (variant.matchesKey(GooseVariants.WARM)) {
            return new ItemStack(SillyGooseItems.SMALL_WHITE_EGG);
        }

        return new ItemStack(SillyGooseItems.WHITE_EGG);
    }

    public static boolean canSpawn(EntityType<GooseEntity> type, ServerWorldAccess world, SpawnReason spawnReason, BlockPos pos, net.minecraft.util.math.random.Random random) {
        if (!AnimalEntity.isValidNaturalSpawn(type, world, spawnReason, pos, random)) {
            return false;
        }

        return hasWaterNearby(world, pos, 12, 5);
    }

    private static boolean hasWaterNearby(ServerWorldAccess world, BlockPos center, int horizontalRadius, int verticalRange) {
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        int waterBlocksFound = 0;
        int requiredWaterBlocks = 6;

        for (int x = -horizontalRadius; x <= horizontalRadius; x += 2) {
            for (int z = -horizontalRadius; z <= horizontalRadius; z += 2) {
                for (int y = -verticalRange; y <= verticalRange; y++) {
                    mutable.set(center.getX() + x, center.getY() + y, center.getZ() + z);

                    if (world.getFluidState(mutable).isIn(FluidTags.WATER)) {
                        waterBlocksFound++;

                        if (waterBlocksFound >= requiredWaterBlocks) {
                            return true;
                        }
                    }
                    else if (world.getBlockState(mutable).isIn(BlockTags.ICE)) {
                        BlockPos below = mutable.down();
                        if (world.getFluidState(below).isIn(FluidTags.WATER)) {
                            waterBlocksFound++;

                            if (waterBlocksFound >= requiredWaterBlocks) {
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SillyGooseSounds.GOOSE_DEATH;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SillyGooseSounds.GOOSE_HURT;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SillyGooseSounds.GOOSE_HONK;
    }

    private void handlePreferenceChange() {
        if (this.isBaby()) {
            return;
        }

        if (this.preferenceChangeTimer <= 0) {
            if (this.random.nextFloat() < 0.75F) {
                this.setPrefersWater(!this.getPrefersWater());
            }
            this.preferenceChangeTimer = MIN_PREFERENCE_CHANGE_TIME +
                    this.random.nextInt(MAX_PREFERENCE_CHANGE_TIME - MIN_PREFERENCE_CHANGE_TIME);
        } else {
            this.preferenceChangeTimer--;
        }
    }

    private void handleOffPreferenceTimer() {
        boolean prefersWater = this.getPrefersWater();
        boolean inWater = this.isTouchingWater();
        boolean inOffPreferenceZone = (prefersWater && !inWater) || (!prefersWater && inWater);

        if (inOffPreferenceZone) {
            if (!this.isInOffPreferenceMode) {
                this.isInOffPreferenceMode = true;
                this.offPreferenceTimer = MIN_OFF_PREFERENCE_TIME +
                        this.random.nextInt(MAX_OFF_PREFERENCE_TIME - MIN_OFF_PREFERENCE_TIME);
            } else {
                this.offPreferenceTimer--;
            }
        } else {
            this.isInOffPreferenceMode = false;
            this.offPreferenceTimer = 0;
        }
    }

    public boolean shouldReturnToPreference() {
        return this.isInOffPreferenceMode && this.offPreferenceTimer <= 0;
    }

    private void handleWaterFloating() {
        if (!this.isTouchingWater() || this.hasVehicle()) {
            return;
        }

        BlockPos pos = this.getBlockPos();

        double entityHeight = this.isBaby() ? 1.375 * 0.7 : 1.375;
        int topBlockOffset = (int) Math.ceil(entityHeight);
        BlockPos airCheckPos = pos.up(topBlockOffset);

        boolean hasAirAbove = !this.getEntityWorld().getFluidState(airCheckPos).isIn(FluidTags.WATER) &&
                this.getEntityWorld().getBlockState(airCheckPos).isAir();

        if (!hasAirAbove) {
            Vec3d velocity = this.getVelocity();
            double surfaceBoost = this.isBaby() ? 0.12 : 0.10;
            this.setVelocity(velocity.x, surfaceBoost, velocity.z);
            this.velocityDirty = true;
            return;
        }

        double waterSurfaceY = pos.getY() + 1.0;
        BlockPos checkPos = new BlockPos((int)this.getX(), (int)waterSurfaceY, (int)this.getZ());
        while (this.getEntityWorld().getFluidState(checkPos).isIn(FluidTags.WATER)) {
            waterSurfaceY += 1.0;
            checkPos = new BlockPos((int)this.getX(), (int)waterSurfaceY, (int)this.getZ());
        }

        float floatOffset = this.isBaby() ? (3.75F / 16.0F) : (5.5F / 16.0F);
        double targetY = waterSurfaceY - floatOffset;
        double currentY = this.getY();
        double yDiff = targetY - currentY;

        Vec3d velocity = this.getVelocity();
        boolean isSwimming = Math.abs(velocity.x) > 0.02 || Math.abs(velocity.z) > 0.02;

        if (isSwimming) {
            boolean nearLand = false;
            BlockPos[] checkPositions = {
                    pos.north(), pos.south(), pos.east(), pos.west()
            };

            for (BlockPos landCheck : checkPositions) {
                if (!this.getEntityWorld().getFluidState(landCheck).isIn(FluidTags.WATER) &&
                        this.getEntityWorld().getBlockState(landCheck).isSolidBlock(this.getEntityWorld(), landCheck)) {
                    nearLand = true;
                    break;
                }
                BlockPos upOne = landCheck.up();
                if (!this.getEntityWorld().getFluidState(upOne).isIn(FluidTags.WATER) &&
                        this.getEntityWorld().getBlockState(upOne).isSolidBlock(this.getEntityWorld(), upOne)) {
                    nearLand = true;
                    break;
                }
            }

            if (nearLand) {
                double boostStrength = this.isBaby() ? 0.15 : 0.14;
                this.setVelocity(velocity.x, Math.max(velocity.y, boostStrength), velocity.z);
                this.velocityDirty = true;
                return;
            }
        }

        if (!this.isOnGround() && Math.abs(yDiff) < 2.0) {
            double newYVelocity;

            if (yDiff > 0.1) {
                newYVelocity = Math.min(0.08, yDiff * 0.1);
            } else if (yDiff < -0.1) {
                newYVelocity = Math.max(-0.03, yDiff * 0.1);
            } else {
                newYVelocity = velocity.y * 0.5;
            }

            if (isSwimming) {
                newYVelocity = velocity.y * 0.8 + newYVelocity * 0.2;
            }

            this.setVelocity(velocity.x, newYVelocity, velocity.z);
            this.velocityDirty = true;
        }
    }

    private void setupAnimationStates() {
        boolean inWater = this.isTouchingWater();
        boolean isMovingHorizontally = this.getVelocity().horizontalLengthSquared() > 0.001;
        boolean isInHitAndRun = this.isInHitAndRunMode();
        boolean isRunning = isMovingHorizontally && isInHitAndRun;

        if (this.idleAnimationTimeout <= 0) {
            this.idleAnimationTimeout = 2;

            AnimationState targetAnimation = null;

            if (inWater) {
                if (isRunning) {
                    targetAnimation = this.swimFastAnimationState;
                } else if (isMovingHorizontally) {
                    targetAnimation = this.swimAnimationState;
                } else {
                    targetAnimation = this.idleWaterAnimationState;
                }
            } else {
                if (isRunning) {
                    targetAnimation = this.runAnimationState;
                } else if (isMovingHorizontally) {
                    targetAnimation = this.walkAnimationState;
                } else if (isInHitAndRun) {
                    targetAnimation = this.wingsUpIdleAnimationState;
                } else {
                    targetAnimation = this.idleAnimationState;
                }
            }

            if (targetAnimation != null && !targetAnimation.isRunning()) {
                this.idleAnimationState.stop();
                this.idleWaterAnimationState.stop();
                this.walkAnimationState.stop();
                this.runAnimationState.stop();
                this.swimAnimationState.stop();
                this.swimFastAnimationState.stop();
                this.wingsUpIdleAnimationState.stop();

                targetAnimation.start(this.age);
            }
        } else {
            --this.idleAnimationTimeout;
        }
    }

    public boolean isInHitAndRunMode() {
        return this.dataTracker.get(IS_IN_HIT_AND_RUN_MODE);
    }

    public void setInHitAndRunMode(boolean inMode) {
        this.dataTracker.set(IS_IN_HIT_AND_RUN_MODE, inMode);
    }

    @Override
    public boolean canPickupItem(ItemStack stack) {
        if (this.isBaby()) {
            return false;
        }

        EquipmentSlot equipmentSlot = this.getPreferredEquipmentSlot(stack);
        if (!this.getEquippedStack(equipmentSlot).isEmpty()) {
            return false;
        }
        return equipmentSlot == EquipmentSlot.MAINHAND &&
                stack.isIn(ItemTags.SWORDS) &&
                this.canPickUpLoot();
    }

    @Override
    public boolean canPickUpLoot() {
        return this.dataTracker.get(CAN_PICKUP_LOOT);
    }

    public void setCanPickUpLoot(boolean canPickUpLoot) {
        this.dataTracker.set(CAN_PICKUP_LOOT, canPickUpLoot);
    }

    @Override
    protected void loot(ServerWorld world, ItemEntity itemEntity) {
        ItemStack stack = itemEntity.getStack();
        if (this.canPickupItem(stack)) {
            this.triggerItemPickedUpByEntityCriteria(itemEntity);
            this.equipStack(EquipmentSlot.MAINHAND, stack.split(1));
            this.setEquipmentDropChance(EquipmentSlot.MAINHAND, 2.0F);
            this.sendPickup(itemEntity, stack.getCount());
            if (stack.isEmpty()) {
                itemEntity.discard();
            }
        }
    }

    @Override
    public ActionResult interactMob(PlayerEntity player, Hand hand) {
        ItemStack stackInHand = player.getStackInHand(hand);
        if (stackInHand.isIn(ItemTags.CHICKEN_FOOD) &&
                !this.getEquippedStack(EquipmentSlot.MAINHAND).isEmpty() &&
                this.getEquippedStack(EquipmentSlot.MAINHAND).isIn(ItemTags.SWORDS)) {

            if (!this.getEntityWorld().isClient()) {
                ItemStack sword = this.getEquippedStack(EquipmentSlot.MAINHAND).copy();
                ItemEntity itemEntity = new ItemEntity(
                        this.getEntityWorld(),
                        this.getX(),
                        this.getY() + 0.5,
                        this.getZ(),
                        sword
                );
                this.getEntityWorld().spawnEntity(itemEntity);
                this.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);

                this.setCanPickUpLoot(false);
                this.swordPickupCooldownTimer = 400;
                if (!player.getAbilities().creativeMode) {
                    stackInHand.decrement(1);
                }

                if (player instanceof ServerPlayerEntity serverPlayer) {
                    UnfairTradeAdvancementHandler.grantUnfairTradeAdvancement(serverPlayer);
                }
                this.playSound(SoundEvents.ENTITY_GENERIC_EAT.value(), 1.0F, 1.0F);
            }

            return ActionResult.SUCCESS;
        }

        return super.interactMob(player, hand);
    }

    private void handleSwordPickupCooldown() {
        if (this.swordPickupCooldownTimer > 0) {
            this.swordPickupCooldownTimer--;

            if (this.swordPickupCooldownTimer <= 0) {
                this.setCanPickUpLoot(true);
            }
        }
    }

    @Nullable
    public GooseEntity createChild(ServerWorld serverWorld, PassiveEntity passiveEntity) {
        GooseEntity gooseEntity = (GooseEntity)SillyGooseEntities.GOOSE.create(serverWorld, SpawnReason.BREEDING);
        if (gooseEntity != null && passiveEntity instanceof GooseEntity gooseEntity2) {
            gooseEntity.setVariant(this.random.nextBoolean() ? this.getVariant() : gooseEntity2.getVariant());
            gooseEntity.setPrefersWater(this.getPrefersWater());
        }

        return gooseEntity;
    }

    @Override
    public EntityData initialize(ServerWorldAccess world, LocalDifficulty difficulty, SpawnReason spawnReason, @Nullable EntityData entityData) {
        if (spawnReason != SpawnReason.TRIGGERED && spawnReason != SpawnReason.BREEDING && spawnReason != SpawnReason.COMMAND) {
            Variants.select(SpawnContext.of(world, this.getBlockPos()), SillyGooseRegistryKeys.GOOSE_VARIANT)
                    .ifPresent(this::setVariant);
        }

        if (!this.isBaby()) {
            this.preferenceChangeTimer = MIN_PREFERENCE_CHANGE_TIME +
                    this.random.nextInt(MAX_PREFERENCE_CHANGE_TIME - MIN_PREFERENCE_CHANGE_TIME);

            this.setCanPickUpLoot(this.random.nextFloat() < CHANCE_OF_PICK_UP_WEAPON);

            this.eggLayTimer = MIN_EGG_LAY_TIME +
                    this.random.nextInt(MAX_EGG_LAY_TIME - MIN_EGG_LAY_TIME);

            if (spawnReason == SpawnReason.NATURAL && this.random.nextFloat() < 0.3F) {
                entityData = new GooseGroupData(true);
            }

            if (this.random.nextFloat() < 0.05F) {
                float swordRoll = this.random.nextFloat();
                ItemStack spawnSword;
                if (swordRoll < 0.50F) {
                    spawnSword = new ItemStack(Items.WOODEN_SWORD);
                } else if (swordRoll < 0.85F) {
                    spawnSword = new ItemStack(Items.STONE_SWORD);
                } else {
                    spawnSword = new ItemStack(Items.IRON_SWORD);
                }
                int maxDamage = spawnSword.getMaxDamage();
                int damage = this.random.nextInt((int)(maxDamage * 0.25F) + 1);
                spawnSword.setDamage(damage);
                this.equipStack(EquipmentSlot.MAINHAND, spawnSword);
                this.setEquipmentDropChance(EquipmentSlot.MAINHAND, 2.0F);
            }
        }

        if (entityData instanceof GooseGroupData groupData && groupData.shouldSpawnBabies()) {
            int babyCount = 1 + this.random.nextInt(3);

            for (int i = 0; i < babyCount; i++) {
                GooseEntity baby = (GooseEntity) SillyGooseEntities.GOOSE.create(world.toServerWorld(), SpawnReason.NATURAL);
                if (baby != null) {
                    baby.setBaby(true);
                    baby.setVariant(this.getVariant());
                    baby.setPrefersWater(this.getPrefersWater());

                    double offsetX = this.random.nextGaussian() * 0.5;
                    double offsetZ = this.random.nextGaussian() * 0.5;
                    baby.refreshPositionAndAngles(
                            this.getX() + offsetX,
                            this.getY(),
                            this.getZ() + offsetZ,
                            this.random.nextFloat() * 360.0F,
                            0.0F
                    );

                    world.spawnEntity(baby);
                }
            }
        }

        return super.initialize(world, difficulty, spawnReason, entityData);
    }


    public static class GooseGroupData extends PassiveEntity.PassiveData {
        private final boolean shouldSpawnBabies;

        public GooseGroupData(boolean shouldSpawnBabies) {
            super(false);
            this.shouldSpawnBabies = shouldSpawnBabies;
        }

        public boolean shouldSpawnBabies() {
            return this.shouldSpawnBabies;
        }
    }

    @Override
    public void onGrowUp() {
        super.onGrowUp();
        if (this.preferenceChangeTimer == 0) {
            this.preferenceChangeTimer = MIN_PREFERENCE_CHANGE_TIME +
                    this.random.nextInt(MAX_PREFERENCE_CHANGE_TIME - MIN_PREFERENCE_CHANGE_TIME);
        }

        if (this.eggLayTimer == 0) {
            this.eggLayTimer = MIN_EGG_LAY_TIME +
                    this.random.nextInt(MAX_EGG_LAY_TIME - MIN_EGG_LAY_TIME);
        }
    }

    public void setVariant(RegistryEntry<GooseVariant> variant) {
        this.dataTracker.set(VARIANT, variant);
    }

    public RegistryEntry<GooseVariant> getVariant() {
        return (RegistryEntry)this.dataTracker.get(VARIANT);
    }

    public boolean getPrefersWater() {
        return this.dataTracker.get(PREFERS_WATER);
    }

    public void setPrefersWater(boolean prefersWater) {
        this.dataTracker.set(PREFERS_WATER, prefersWater);
    }

    @Nullable
    public <T> T get(ComponentType<? extends T> type) {
        return type == SillyGooseDataComponentTypes.GOOSE_VARIANT
                ? castComponentValue(type, new LazyRegistryEntryReference<>(this.getVariant()))
                : super.get(type);
    }

    protected void copyComponentsFrom(ComponentsAccess from) {
        this.copyComponentFrom(from, SillyGooseDataComponentTypes.GOOSE_VARIANT);
        super.copyComponentsFrom(from);
    }

    @Override
    public float getScaleFactor() {
        return this.isBaby() ? 0.7F : 1.0F;
    }

    protected <T> boolean setApplicableComponent(ComponentType<T> type, T value) {
        if (type == SillyGooseDataComponentTypes.GOOSE_VARIANT) {
            LazyRegistryEntryReference<GooseVariant> lazyRef = (LazyRegistryEntryReference<GooseVariant>) castComponentValue(SillyGooseDataComponentTypes.GOOSE_VARIANT, value);
            lazyRef.resolveEntry(this.getRegistryManager()).ifPresent(this::setVariant);
            return true;
        } else {
            return super.setApplicableComponent(type, value);
        }
    }

    @Override
    public boolean isBreedingItem(ItemStack stack) {
        return stack.isIn(ItemTags.CHICKEN_FOOD);
    }

    static {
        VARIANT = DataTracker.registerData(GooseEntity.class, SillyGooseTrackedDataHandlerRegistry.GOOSE_VARIANT);
        PREFERS_WATER = DataTracker.registerData(GooseEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
        CAN_PICKUP_LOOT = DataTracker.registerData(GooseEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
        IS_IN_HIT_AND_RUN_MODE = DataTracker.registerData(GooseEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    }


}
