package im.wma.dev.creepair;

import static com.google.common.base.Objects.toStringHelper;

import com.google.common.base.Objects;
import org.slf4j.Logger;
import org.spongepowered.api.block.BlockSnapshot;
import org.spongepowered.api.block.BlockState;
import org.spongepowered.api.block.BlockTypes;
import org.spongepowered.api.entity.living.monster.Creeper;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * This class is for storing the Creeper that caused the mend, its full list
 * of captured blocks that were affected, and processing the mend
 */
public class Mend {

    private final Creeper source;

    // This holds the whole state of the explosion that just happened
    private final List<BlockSnapshot> capturedBlocks = new ArrayList<>();
    // This just holds the blocks that are considered "mendable"
    private final List<BlockSnapshot> pendingMends = new ArrayList<>();

    // We sort once on our first process since at that point we know it's safe
    private boolean hasSorted = false;

    // Logging is useful for debugging
    private static Logger logger;

    public Mend(Creeper source) {
        this.source = source;
    }

    // Used by CreepairPlugin to initialize the static logger
    public static void setLogger(Logger logger) {
        Mend.logger = logger;
    }

    /**
     * Add a {@link BlockSnapshot} to the captured blocks
     * @param captured The BlockSnapshot captured
     */
    public void captureBlock(BlockSnapshot captured) {
        this.capturedBlocks.add(captured);
    }

    /**
     * Add a {@link BlockSnapshot} to the pending mends
     * @param toMend The BlockSnapshot to be mended
     */
    public void mendBlock(BlockSnapshot toMend) {
        this.pendingMends.add(toMend);
    }

    /**
     * Process the remaining mendable {@link BlockSnapshot}s until none are left.
     *
     * @param maxSnapshotsProcessed The maximum number to be mended during a single process
     * @return If this mend will need anymore processing
     */
    public boolean processMend(int maxSnapshotsProcessed) {
        if (!hasSorted) {
            pendingMends.sort((o1, o2) -> o1.getPosition().getY() - o2.getPosition().getY());
            hasSorted = true;
        }
        int snapshotsProcessed = Math.min(maxSnapshotsProcessed, pendingMends.size());
        for (int i = 0; i < snapshotsProcessed; i++) {
            BlockSnapshot snapshot = pendingMends.get(0);
            Mend.logger.trace("[Mend/processMend({})] Restoring: {}", maxSnapshotsProcessed, snapshot);
            snapshot.restore(true, false);
            pendingMends.remove(0);
        }
        // Remember true = has more work to do
        return !pendingMends.isEmpty();
    }

    /**
     * Checks if the list of snapshots captured from the explosion contains
     * the given snapshot.
     *
     * <p>Note: This is not an exact comparison as that would be a problem
     * for item drops, so we compare against {@link BlockState}, extended
     * BlockState and {@link Location} to do a reasonably accurate match.</p>
     *
     * @param snapshot The snapshot to match against
     * @return If the snapshot was in the explosion
     */
    public boolean containsSnapshot(BlockSnapshot snapshot) {
        boolean hasEqual = false;
        for (BlockSnapshot capturedSnapshot : capturedBlocks) {
            if (capturedSnapshot.getLocation().equals(snapshot.getLocation()) &&
                    capturedSnapshot.getState().equals(snapshot.getState()) &&
                    capturedSnapshot.getExtendedState().equals(capturedSnapshot.getExtendedState())) {
                hasEqual = true;
                break;
            }
        }
        return hasEqual;
    }

    /**
     * Checks if the list of snapshots to mend contains the given snapshot.
     *
     * @param snapshot The snapshot to match against
     * @return If the snapshot was in the mend list
     */
    public boolean mendsContainsSnapshot(BlockSnapshot snapshot) {
        return pendingMends.contains(snapshot);
    }

    /**
     * Gets the source of the explosion, and subsequent mend.
     *
     * @return The Creeper causing this mend
     */
    public Creeper getSource() {
        return source;
    }

    @Override
    public String toString() {
        return toStringHelper(this)
                .add("source", source)
                .add("capturedBlocks", capturedBlocks)
                .add("pendingMends", pendingMends).toString();
    }
}
