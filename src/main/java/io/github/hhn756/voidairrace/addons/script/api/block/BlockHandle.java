package io.github.hhn756.voidairrace.addons.script.api.block;

import io.github.hhn756.voidairrace.addons.script.MutationAccess;
import io.github.hhn756.voidairrace.addons.script.api.arena.ArenaHandle;
import io.github.hhn756.voidairrace.addons.script.simplevalue.BlockPos;
import net.sandius.rembulan.ByteString;
import net.sandius.rembulan.LuaRuntimeException;
import net.sandius.rembulan.StateContext;
import net.sandius.rembulan.Table;
import net.sandius.rembulan.impl.NonsuspendableFunctionException;
import net.sandius.rembulan.runtime.AbstractFunction0;
import net.sandius.rembulan.runtime.ExecutionContext;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.function.Function;

/**
 * 方块句柄根类：竞技场世界内某一格方块在脚本侧的基础表示，由 {@code arena.get_block(pos, type)} 取得<br>
 * 根类持有唯一的 Lua 表并安装全部通用函数；子类只在构造器里经 {@link #addFunction}
 * 往同一张表追加专属函数，不新增实例字段，也不自行建表——公共函数面因此不会漏装
 * <p>
 * 取得句柄时不与预期类型比对，也不因该格内容抛错；实际方块只参与大类判定。
 * 比对推迟到每次函数调用前，经借据重新解析世界与坐标时一并复查——竞技场已归还
 * 或该格方块不是预期材质时抛 Lua 错误。子类函数不得缓存方块状态
 * （{@code Container}、{@code Sign}、{@code BlockData} 等），一律从 {@link #requireBlock} 重取
 * <p>
 * 预期类型是脚本对那格方块的断言，不是查询结果，也不会改写方块；它如何映射到
 * 句柄类见 {@link BlockHandleResolver}：精确材质表 → 大类 → 本基类兜底
 * */
public class BlockHandle {
    /** 借据来源：借据有效性、世界解析、修改权校验都经它 */
    private final @NonNull ArenaHandle arena;

    /** 方块坐标 */
    private final @NonNull BlockPos pos;

    /** 预期类型对应的材质：取得时不比对，每次函数调用都据此复查 */
    private final @NonNull Material material;

    /** 本句柄唯一的 Lua 表：根类建好后由子类往里追加函数 */
    private final @NonNull Table table;

    BlockHandle(
            @NonNull ArenaHandle arena,
            @NonNull BlockPos pos,
            @NonNull Material material
    ) {
        this.arena = arena;
        this.pos = pos;
        this.material = material;
        this.table = arena.stateContext().newTable();

        installCommon(this.table);
    }

    /**
     * 向根表追加一个 Lua 函数；同名键会被覆盖，子类构造器专用
     *
     * @param name     脚本侧函数名
     * @param function Rembulan 函数对象
     * */
    protected final void addFunction(@NonNull String name, @NonNull Object function) {
        table.rawset(ByteString.of(name), function);
    }

    /**
     * @return 本句柄唯一的 Lua 表
     * */
    public final @NonNull Table table() {
        return table;
    }

    /**
     * 重新解析本句柄对应的方块，并复查是否为预期材质
     *
     * @return 当前方块
     *
     * @throws LuaRuntimeException 竞技场借据失效，或该格方块不是预期材质时抛出
     * */
    final @NonNull Block requireBlock() {
        World world = arena.requireWorld();
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        Material actual = block.getType();
        if (actual != material) {
            throw new LuaRuntimeException("坐标 (" + pos.x() + ", " + pos.y() + ", " + pos.z()
                    + ") 的方块不是预期材质 " + material.getKey() + "（当前为 " + actual.getKey() + "）");
        }
        return block;
    }

    /** 校验竞技场修改权：专属函数中的写操作调用前须经此检查 */
    final void requireMutation() {
        MutationAccess.requireArena(arena.pkg(), arena.requireToken());
    }

    /** 方块坐标 */
    final @NonNull BlockPos pos() {
        return pos;
    }

    /** 共享 Lua 状态上下文（建返回表用） */
    final @NonNull StateContext stateContext() {
        return arena.stateContext();
    }

    // ------ 通用函数 ------

    /** 安装与材质无关的通用函数 */
    private void installCommon(@NonNull Table table) {
        table.rawset(ByteString.of("pos"), new Pos(this));
        table.rawset(ByteString.of("type"), new ReadProperty(this,
                block -> ByteString.of(block.getType().getKey().toString())));
        table.rawset(ByteString.of("hardness"), new ReadProperty(this,
                block -> (double) block.getType().getHardness()));
        table.rawset(ByteString.of("blast_resistance"), new ReadProperty(this,
                block -> (double) block.getType().getBlastResistance()));
        table.rawset(ByteString.of("slipperiness"), new ReadProperty(this,
                block -> (double) block.getType().getSlipperiness()));
        table.rawset(ByteString.of("is_solid"), new ReadProperty(this, Block::isSolid));
        table.rawset(ByteString.of("is_liquid"), new ReadProperty(this, Block::isLiquid));
        table.rawset(ByteString.of("is_passable"), new ReadProperty(this, Block::isPassable));
        table.rawset(ByteString.of("is_flammable"), new ReadProperty(this,
                block -> block.getType().isFlammable()));
        table.rawset(ByteString.of("has_gravity"), new ReadProperty(this,
                block -> block.getType().hasGravity()));
        table.rawset(ByteString.of("is_occluding"), new ReadProperty(this,
                block -> block.getType().isOccluding()));
    }

    /**
     * {@code block.pos()}：返回方块坐标表 {@code {x=, y=, z=}}（整数）
     * */
    private static final class Pos extends AbstractFunction0 {
        private final @NonNull BlockHandle handle;

        Pos(@NonNull BlockHandle handle) {
            this.handle = handle;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            handle.requireBlock();
            context.getReturnBuffer().setTo(BlockPos.toLua(handle.stateContext(), handle.pos()));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }

    /**
     * 通用只读函数的统一实现：先重新解析方块，再交给读取函数取当前值<br>
     * 读取函数只拿 {@link Block}，不持有世界或借据
     * */
    private static final class ReadProperty extends AbstractFunction0 {
        private final @NonNull BlockHandle handle;
        private final @NonNull Function<@NonNull Block, @NonNull Object> reader;

        ReadProperty(
                @NonNull BlockHandle handle,
                @NonNull Function<@NonNull Block, @NonNull Object> reader
        ) {
            this.handle = handle;
            this.reader = reader;
        }

        @Override
        public void invoke(@NonNull ExecutionContext context) {
            context.getReturnBuffer().setTo(reader.apply(handle.requireBlock()));
        }

        @Override
        public void resume(@NonNull ExecutionContext context, @Nullable Object suspendedState)
                throws NonsuspendableFunctionException {
            throw new NonsuspendableFunctionException(this.getClass());
        }
    }
}
