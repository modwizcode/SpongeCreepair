package im.wma.dev.creepair;

import com.google.common.collect.Lists;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.spongepowered.api.Game;
import org.spongepowered.api.block.BlockSnapshot;
import org.spongepowered.api.block.BlockType;
import org.spongepowered.api.block.BlockTypes;
import org.spongepowered.api.command.CommandCallable;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.args.GenericArguments;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.data.Transaction;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.EntityTypes;
import org.spongepowered.api.entity.Item;
import org.spongepowered.api.entity.living.monster.Creeper;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.block.NotifyNeighborBlockEvent;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.entity.spawn.BlockSpawnCause;
import org.spongepowered.api.event.cause.entity.spawn.EntitySpawnCause;
import org.spongepowered.api.event.cause.entity.spawn.SpawnTypes;
import org.spongepowered.api.event.filter.cause.First;
import org.spongepowered.api.event.filter.cause.Named;
import org.spongepowered.api.event.game.state.GameInitializationEvent;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.event.item.inventory.DropItemEvent;
import org.spongepowered.api.event.world.ExplosionEvent;
import org.spongepowered.api.item.ItemTypes;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.world.World;
import org.spongepowered.api.world.explosion.Explosion;
import org.spongepowered.api.world.storage.WorldProperties;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

@Plugin(id = "creepair", name = "Creepair", version = "0.1.0")
public class CreepairPlugin {

    @Inject private Logger logger;
    @Inject private Game game;

    private List<Mend> remainingMends = new ArrayList<>();
    private Task fixApplicationTask = null;

    private List<BlockType> protectedTypes = Lists.newArrayList(BlockTypes.DIRT, BlockTypes.GRASS, BlockTypes.TALLGRASS, BlockTypes.STONE,
            BlockTypes.GRAVEL, BlockTypes.SAND, BlockTypes.ICE, BlockTypes.PACKED_ICE, BlockTypes.VINE, BlockTypes.MOSSY_COBBLESTONE, BlockTypes
                    .SANDSTONE);
    private int processPerRun = 5;

    @Listener
    public void initializeStatics(GamePreInitializationEvent event) {
        Mend.setLogger(logger);
    }

    @Listener
    public void onGameInitialize(GameInitializationEvent event) {
        registerCommands();
        scheduleMendingTask(10);
    }

    private void registerCommands() {
        CommandCallable command = CommandSpec.builder()
                .description(Text.of("A test command to poke at events with creepair"))
                .child(CommandSpec.builder().executor((src, args) -> {
                    args.getOne("period").ifPresent(o -> scheduleMendingTask((int) o));
                    return args.getOne("period").isPresent() ? CommandResult.success() : CommandResult.empty();
                }).arguments(GenericArguments.integer(Text.of("period"))).build(), "period")
                .child(CommandSpec.builder().executor((src, args) -> {
                    args.getOne("process").ifPresent(o -> processPerRun = (int) o);
                    return args.getOne("process").isPresent() ? CommandResult.success() : CommandResult.empty();
                }).arguments(GenericArguments.integer(Text.of("process"))).build(), "process")
                .executor(this::spawnTestCreeper)
                .build();
        game.getCommandManager().register(this, command, "creepair");
    }

    private CommandResult spawnTestCreeper(CommandSource src, CommandContext context) {
        WorldProperties mainWorldProps = game.getServer().getDefaultWorld().get();
        World mainWorld = game.getServer().loadWorld(mainWorldProps).get();
        mainWorld.getSpawnLocation().setBlockType(BlockTypes.BOOKSHELF);
        Creeper creeper = (Creeper) mainWorld.createEntity(EntityTypes.CREEPER, mainWorld.getSpawnLocation().getPosition()).get();
        mainWorld.spawnEntity(creeper, Cause.source(EntitySpawnCause.builder().entity(creeper).type(SpawnTypes.PLUGIN).build()).build());
        creeper.ignite();
        return CommandResult.success();
    }

    private void scheduleMendingTask(int period) {
        if (fixApplicationTask != null) {
            fixApplicationTask.cancel();
        }
        fixApplicationTask = game.getScheduler().createTaskBuilder()
                .name("Restore Creeper Damage")
                .intervalTicks(period)
                .execute(() -> {
                    if (!remainingMends.isEmpty()) {
                        List<Mend> toRemove = new ArrayList<>();
                        remainingMends.forEach(mend -> {
                            if (!mend.processMend(processPerRun)) {
                                toRemove.add(mend);
                            }
                        });
                        remainingMends.removeAll(toRemove);
                        toRemove.clear();
                    }
                }).submit(this);
    }

    @Listener
    public void captureRestores(ExplosionEvent.Detonate event, @Named("Source") Creeper source) {
        logger.debug("capture cause: {}", event.getCause());
        Mend mend = new Mend(source);
        event.getTransactions().stream().map(Transaction::getOriginal).forEach(mend::captureBlock);
        event.getTransactions().stream().map(Transaction::getOriginal).filter(blockSnapshot -> protectedTypes.stream().anyMatch(blockType ->
                blockType.equals(blockSnapshot.getState().getType()))).forEach(mend::mendBlock);
        remainingMends.add(mend);
    }

    @Listener
    public void preventNotifies(NotifyNeighborBlockEvent event, @First BlockSnapshot source, @Named("ParentSource") Creeper parentCause) {
        for (Mend remainingMend : remainingMends) {
            if (remainingMend.getSource().equals(parentCause) || remainingMend.containsSnapshot(source)) {
                logger.debug("Notify canceled: {}", event.getCause());
                event.setCancelled(true);
                break;
            }
        }
    }

    @Listener
    public void onDrop(DropItemEvent.Destruct event) {
        for (Entity entity : event.getEntities()) {
            if (entity.getType().equals(EntityTypes.ITEM)) {
                if (((Item) entity).getItemType().equals(ItemTypes.SAPLING)) {
                    logger.info("Drop cause: {}", event.getCause());
                }
            }
        }
    }
    @Listener
    public void preventDrops(DropItemEvent.Destruct event, @First BlockSpawnCause source) {
        for (Mend remainingMend : remainingMends) {
            if (remainingMend.containsSnapshot(source.getBlockSnapshot())) {
                logger.debug("Drops cancelled: {}", event.getCause());
                logger.trace("dropped entities: {}", event.getEntities());
                event.setCancelled(true);
                break;
            }
        }
    }

    @Nonnull
    private Explosion.Builder fillExplosion(Explosion original) {
        return Explosion.builder()
                .canCauseFire(original.canCauseFire())
                .origin(original.getOrigin())
                .radius(original.getRadius())
                .shouldBreakBlocks(original.shouldBreakBlocks())
                .shouldDamageEntities(original.shouldDamageEntities())
                .shouldPlaySmoke(original.shouldPlaySmoke())
                .sourceExplosive(original.getSourceExplosive().orElse(null))
                .world(original.getWorld());
    }
}
