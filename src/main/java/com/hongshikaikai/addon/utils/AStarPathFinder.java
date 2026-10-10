package com.hongshikaikai.addon.utils;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over block positions, for teleport paths that have to go through walls rather than around them.
 *
 * <p>This is the search LiquidBounce's {@code AStarPathBuilder} runs, rewritten for Meteor. The two
 * decisions worth naming:
 *
 * <h2>What "passable" means</h2>
 * A node is passable when the two-block column above its floor is free of block collisions - the
 * space a standing player occupies. It is asked of {@link World#getBlockCollisions}, the same query
 * the movement code itself uses, so a node this search accepts is a node the player could actually
 * be put into. It is not a check on whether a player could <em>walk</em> there: no gravity, no jump
 * height, no fall damage. The caller teleports, so the only question is whether the destination has
 * room for a body.
 *
 * <h2>Why the vertical moves are the expensive ones</h2>
 * The neighbour set is the four horizontal steps plus <em>every</em> vertical step from one to nine
 * blocks, in both directions, and an edge costs the squared distance it covers. A one-block step
 * costs 1, a nine-block climb costs 81. So the search naturally prefers the flat route and only
 * spends a tall climb when the corridor really is the only way through - which is what makes it read
 * a staircase or a drop as one move instead of nine. The heuristic is the squared distance to the
 * goal, the same metric, so it stays admissible.
 *
 * <p>Edges are only ever added between nodes that are already passable, so a path never has to be
 * re-checked for clearance before it is travelled.
 */
public final class AStarPathFinder {
    private AStarPathFinder() {
    }

    /** The four horizontal steps and the vertical steps from one to nine blocks, either way. */
    private static final int[][] DIRECTIONS = {
        {-1, 0, 0},
        {1, 0, 0},
        {0, -9, 0}, {0, -8, 0}, {0, -7, 0}, {0, -6, 0}, {0, -5, 0},
        {0, -4, 0}, {0, -3, 0}, {0, -2, 0}, {0, -1, 0},
        {0, 1, 0}, {0, 2, 0}, {0, 3, 0}, {0, 4, 0}, {0, 5, 0},
        {0, 6, 0}, {0, 7, 0}, {0, 8, 0}, {0, 9, 0},
        {0, 0, -1},
        {0, 0, 1}
    };

    /** The four horizontal diagonals, only used when the caller asks for them. */
    private static final int[][] DIAGONALS = {
        {-1, 0, -1},
        {1, 0, -1},
        {-1, 0, 1},
        {1, 0, 1}
    };

    /**
     * Finds a path of block positions from {@code start} to within {@code stopRange} of {@code end}.
     *
     * <p>The returned list does not contain {@code start}, so it is directly the sequence of
     * positions to move through. An empty list means either that the goal was already inside
     * {@code stopRange} or that no path was found inside the budget - the caller treats both the
     * same way, as "nothing to travel".
     *
     * @param maxCost       the largest total edge cost a path may have. Edges cost their squared
     *                      length, so a flat path may be {@code maxCost} blocks long while a path
     *                      that climbs nine blocks spends 81 of the budget on that one move.
     * @param maxIterations the number of nodes the search may expand before giving up. This is the
     *                      hard bound on how much of a tick the search can take.
     */
    public static List<BlockPos> findPath(World world, Entity entity, BlockPos start, BlockPos end,
                                          boolean allowDiagonal, int maxCost, int maxIterations,
                                          double stopRange) {
        if (start.isWithinDistance(end, stopRange)) return Collections.emptyList();

        Map<BlockPos, Double> gScores = new HashMap<>();
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        PriorityQueue<QueueEntry> open = new PriorityQueue<>(Comparator.comparingDouble(entry -> entry.fScore));

        gScores.put(start, 0.0);
        open.add(new QueueEntry(start, 0.0, start.getSquaredDistance(end)));

        int iterations = 0;
        while (!open.isEmpty()) {
            if (++iterations > maxIterations) break;

            QueueEntry current = open.poll();

            Double bestKnown = gScores.get(current.node);
            if (bestKnown == null || current.gScore > bestKnown || current.gScore > maxCost) continue;

            if (current.node.isWithinDistance(end, stopRange)) {
                return reconstruct(start, current.node, previous);
            }

            for (BlockPos neighbour : neighbours(world, entity, current.node, allowDiagonal)) {
                double candidate = current.gScore + current.node.getSquaredDistance(neighbour);
                if (candidate > maxCost) continue;

                Double known = gScores.get(neighbour);
                if (known != null && candidate >= known) continue;

                gScores.put(neighbour, candidate);
                previous.put(neighbour, current.node);
                open.add(new QueueEntry(neighbour, candidate, candidate + neighbour.getSquaredDistance(end)));
            }
        }

        return Collections.emptyList();
    }

    private static List<BlockPos> neighbours(World world, Entity entity, BlockPos from, boolean allowDiagonal) {
        List<BlockPos> result = new ArrayList<>(allowDiagonal ? 26 : 22);

        for (int[] direction : DIRECTIONS) {
            BlockPos candidate = from.add(direction[0], direction[1], direction[2]);
            if (isPassable(world, entity, candidate)) result.add(candidate);
        }

        if (allowDiagonal) {
            for (int[] direction : DIAGONALS) {
                // A diagonal is only taken when both of the straight sides it cuts across are open
                // too, or the path would squeeze through a corner that has no gap in it.
                if (isPassable(world, entity, from.add(direction[0], 0, 0))
                    && isPassable(world, entity, from.add(0, 0, direction[2]))) {
                    BlockPos candidate = from.add(direction[0], direction[1], direction[2]);
                    if (isPassable(world, entity, candidate)) result.add(candidate);
                }
            }
        }

        return result;
    }

    /**
     * Whether a standing player fits in the block at {@code pos}: the one-by-two column above its
     * floor has to be free of block collisions.
     */
    private static boolean isPassable(World world, Entity entity, BlockPos pos) {
        Box box = new Box(
            pos.getX(), pos.getY(), pos.getZ(),
            pos.getX() + 1.0, pos.getY() + 2.0, pos.getZ() + 1.0
        );

        for (VoxelShape shape : world.getBlockCollisions(entity, box)) {
            if (!shape.isEmpty()) return false;
        }

        return true;
    }

    private static List<BlockPos> reconstruct(BlockPos start, BlockPos goal, Map<BlockPos, BlockPos> previous) {
        List<BlockPos> path = new ArrayList<>();

        for (BlockPos current = goal; current != null && !current.equals(start); current = previous.get(current)) {
            path.add(current);
        }

        Collections.reverse(path);
        return path;
    }

    /** A node queued for expansion, ordered by its estimated total cost. */
    private static final class QueueEntry {
        private final BlockPos node;
        private final double gScore;
        private final double fScore;

        private QueueEntry(BlockPos node, double gScore, double fScore) {
            this.node = node;
            this.gScore = gScore;
            this.fScore = fScore;
        }
    }
}
