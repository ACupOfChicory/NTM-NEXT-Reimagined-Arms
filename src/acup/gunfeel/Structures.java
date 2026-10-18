package acup.gunfeel;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import com.hbm.blockentity.BlockEntityDoorGeneric;
import com.hbm.blockentity.machine.BlockEntityCargoElevator;
import com.hbm.blocks.generic.BlockDoorGeneric;
import com.hbm.blocks.machine.BlockCargoElevator;
import com.hbm.blocks.multiblock.BlockMultiblockCore;
import com.hbm.blocks.multiblock.MultiblockSurface;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

// NTM multiblocks drawn as one big model (the cargo door's leaves, the cargo elevator's posts): the cells a bullet
// meets in each of their blocks, made by the client from what it draws (MeshShapes.structures) and kept like block
// shapes, under the core's state (facing turned to north) and the block's place from the core, turned the same way:
// "hbm:cargo_door[facing=north,open=false,skin=0]@1,2,0". One capture serves every facing.
// Listed under "structures" in ntmgunfeel-bullet-shapes.properties. A block of one with no cells known yet, or a door
// on the move, keeps NTM's hitbox.
public final class Structures {
    private Structures() {
    }

    private static @Nullable Set<Block> listed;

    public static synchronized Set<Block> listed() {
        if (listed != null) return listed;
        Set<Block> out = new HashSet<>();
        try (var in = Structures.class.getResourceAsStream("/ntmgunfeel-bullet-shapes.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String id : p.getProperty("structures", "").split(",")) {
                    Identifier i = Identifier.tryParse(id.trim());
                    if (i != null) BuiltInRegistries.BLOCK.getOptional(i).ifPresent(out::add);
                }
            }
        } catch (Exception e) {
            GunFeel.LOGGER.warn("Could not read the bullet structure list", e);
        }
        return listed = out;
    }

    // the core of the listed multiblock this block belongs to, or null
    public static @Nullable BlockPos core(Level level, BlockPos pos, BlockState state) {
        if (!MultiblockSurface.isSurface(state) || listed().isEmpty()) return null;
        BlockPos core;
        try {
            core = MultiblockSurface.coreOfAny(level, pos, state);
        } catch (Throwable t) {
            return null;
        }
        if (core == null) return null;
        BlockState coreState = level.getBlockState(core);
        return listed().contains(coreState.getBlock()) && coreState.hasProperty(BlockMultiblockCore.FACING) ? core : null;
    }

    // its model stands still: a door shut or open, not on its way (its cells are then NTM's)
    public static boolean atRest(Level level, BlockPos core, BlockState coreState) {
        if (coreState.getBlock() instanceof BlockDoorGeneric) {
            return level.getBlockEntity(core) instanceof BlockEntityDoorGeneric door && (door.state == 0 || door.state == 1);
        }
        return true;
    }

    // the elevator's sections above its base are all alike: one layer stands for them
    public static int layer(BlockState coreState, int dy) {
        return coreState.getBlock() instanceof BlockCargoElevator ? Math.min(dy, 1) : dy;
    }

    // the renderer's model counts (a door's leaves); not the elevator's: its platform moves, NTM's box stays for it
    public static boolean rendered(BlockState coreState) {
        return !(coreState.getBlock() instanceof BlockCargoElevator);
    }

    // turns the core's facing to north
    public static Rotation toNorth(Direction facing) {
        return switch (facing) {
            case EAST -> Rotation.COUNTERCLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.CLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    // the key of the block at (dx, dy, dz) from the core (in the world)
    public static String key(BlockState coreState, int dx, int dy, int dz) {
        Rotation r = toNorth(coreState.getValue(BlockMultiblockCore.FACING));
        BlockPos o = new BlockPos(dx, layer(coreState, dy), dz).rotate(r);
        BlockState north = coreState.setValue(BlockMultiblockCore.FACING, Direction.NORTH);
        // a door's skins are one model with other colours: skin 0 stands for all
        if (north.hasProperty(BlockDoorGeneric.SKIN)) north = north.setValue(BlockDoorGeneric.SKIN, 0);
        return PassableClip.key(north) + "@" + o.getX() + "," + o.getY() + "," + o.getZ();
    }

    // a cell (x, z) of a block turned by r, as BlockPos.rotate turns the block: {x, z}
    public static int[] turn(int x, int z, Rotation r) {
        return switch (r) {
            case CLOCKWISE_90 -> new int[] {15 - z, x};
            case CLOCKWISE_180 -> new int[] {15 - x, 15 - z};
            case COUNTERCLOCKWISE_90 -> new int[] {z, 15 - x};
            default -> new int[] {x, z};
        };
    }

    // the cells (as in the world) of the listed multiblock's block here, or null (none known, a door on its way)
    public static long @Nullable [] cells(Level level, BlockPos pos, BlockState state) {
        BlockPos core = core(level, pos, state);
        if (core == null) return null;
        BlockState coreState = level.getBlockState(core);
        if (!atRest(level, core, coreState)) return null;
        // the elevator's base layer: its cells too when the platform is known as drawn (it lies over the base at the
        // bottom; Structures.solid meets it there), else NTM's hitbox
        if (coreState.getBlock() instanceof BlockCargoElevator && pos.getY() == core.getY() && !ElevatorMesh.available()) return null;
        String key = key(coreState, pos.getX() - core.getX(), pos.getY() - core.getY(), pos.getZ() - core.getZ());
        Rotation r = toNorth(coreState.getValue(BlockMultiblockCore.FACING));
        String world = key + "/" + r.ordinal();
        long[] got = WORLD.get(world);
        if (got != null) return got == NONE ? null : got;
        long[] north = PassableClip.byKey(key);
        long[] out = north == null ? NONE : new long[65];
        if (north != null) {
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int[] n = turn(x, z, r);
                for (int y = 0; y < 16; y++) {
                    if (!PassableClip.cell(north, n[0], y, n[1])) continue;
                    int k = x + 16 * (y + 16 * z);
                    out[k >> 6] |= 1L << (k & 63);
                }
            }
        }
        WORLD.put(world, out);
        return out == NONE ? null : out;
    }

    private static final Map<String, long[]> WORLD = new ConcurrentHashMap<>();
    private static final long[] NONE = new long[0];

    static void clear() {
        WORLD.clear();
    }

    // /gunfeel holedebug: the elevator's platform as this side has it
    static String debug(Level level, BlockPos pos, BlockState state) {
        BlockPos core = core(level, pos, state);
        if (core == null || !(level.getBlockEntity(core) instanceof BlockEntityCargoElevator elevator)) return "";
        return " elevator core=" + core.toShortString() + " extension=" + elevator.extension + " height=" + elevator.height
                + " platform y=" + (core.getY() + 0.75 + elevator.extension) + ".." + (core.getY() + 1.0 + elevator.extension);
    }

    // what the bullet meets in this block besides its cells: the elevator's platform (and piston)
    public static @Nullable BlockHitResult solid(Level level, BlockPos pos, BlockState state, Vec3 from, Vec3 to) {
        BlockPos core = core(level, pos, state);
        if (core == null || !(level.getBlockEntity(core) instanceof BlockEntityCargoElevator elevator)) return null;
        // the platform and piston as drawn: only their shown pixels
        if (ElevatorMesh.available()) return ElevatorMesh.hit(core, elevator.extension, pos, from, to);
        // BlockEntityCargoElevator.shape(): the platform, from the core
        AABB platform = new AABB(-1.0, 0.75 + elevator.extension, -1.0, 2.0, 1.0 + elevator.extension, 2.0)
                .move(core.getX() - pos.getX(), core.getY() - pos.getY(), core.getZ() - pos.getZ());
        // not in this block: checked before intersect(), whose new AABB swaps min and max of boxes that miss each
        // other into a box ending on this block's face
        if (platform.maxX <= 0 || platform.minX >= 1 || platform.maxY <= 0 || platform.minY >= 1 || platform.maxZ <= 0 || platform.minZ >= 1) return null;
        AABB in = platform.intersect(new AABB(0, 0, 0, 1, 1, 1));
        return AABB.clip(List.of(in), from, to, pos);
    }
}
