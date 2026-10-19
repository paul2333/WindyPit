package net.minecraft.server;

import net.minecraft.server.Chunk.EnumTileEntityState;

public class ChunkCache implements IBlockAccess {
	protected int a;
	protected int b;
	protected Chunk[][] c;
	protected boolean d;
	protected World e;

	public ChunkCache(World var1, BlockPosition var2, BlockPosition var3, int var4) {
		this.e = var1;
		this.a = var2.getX() - var4 >> 4;
		this.b = var2.getZ() - var4 >> 4;
		int var5 = var3.getX() + var4 >> 4;
		int var6 = var3.getZ() + var4 >> 4;
		this.c = new Chunk[var5 - this.a + 1][var6 - this.b + 1];
		this.d = true;

		int var7;
		int var8;
		for (var7 = this.a; var7 <= var5; ++var7) {
			for (var8 = this.b; var8 <= var6; ++var8) {
				this.c[var7 - this.a][var8 - this.b] = var1.getChunkAt(var7, var8);
			}
		}

		for (var7 = var2.getX() >> 4; var7 <= var3.getX() >> 4; ++var7) {
			for (var8 = var2.getZ() >> 4; var8 <= var3.getZ() >> 4; ++var8) {
				Chunk var9 = this.c[var7 - this.a][var8 - this.b];
				if (var9 != null && !var9.c(var2.getY(), var3.getY())) {
					this.d = false;
				}
			}
		}

	}
	
	// WindSpigot start - reduce usage of blockposition
	public ChunkCache(World world, int x, int y, int z, int x1, int y1, int z1, int var4) {
		this.e = world;
		this.a = x - var4 >> 4;
		this.b = z - var4 >> 4;
		int var5 = x1 + var4 >> 4;
		int var6 = z1 + var4 >> 4;
		this.c = new Chunk[var5 - this.a + 1][var6 - this.b + 1];
		this.d = true;

		int var7;
		int var8;
		for (var7 = this.a; var7 <= var5; ++var7) {
			for (var8 = this.b; var8 <= var6; ++var8) {
				this.c[var7 - this.a][var8 - this.b] = world.getChunkAt(var7, var8);
			}
		}

		for (var7 = x >> 4; var7 <= x1 >> 4; ++var7) {
			for (var8 = z >> 4; var8 <= z1 >> 4; ++var8) {
				Chunk var9 = this.c[var7 - this.a][var8 - this.b];
				if (var9 != null && !var9.c(y, y1)) {
					this.d = false;
				}
			}
		}

	}
	// WindSpigot end
	
	public TileEntity getTileEntity(BlockPosition var1) {
		int var2 = (var1.getX() >> 4) - this.a;
		int var3 = (var1.getZ() >> 4) - this.b;
		return this.c[var2][var3].a(var1, EnumTileEntityState.IMMEDIATE);
	}

	public IBlockData getType(BlockPosition var1) {
		if (var1.getY() >= 0 && var1.getY() < 256) {
			int var2 = (var1.getX() >> 4) - this.a;
			int var3 = (var1.getZ() >> 4) - this.b;
			if (var2 >= 0 && var2 < this.c.length && var3 >= 0 && var3 < this.c[var2].length) {
				Chunk var4 = this.c[var2][var3];
				if (var4 != null) {
					return var4.getBlockData(var1);
				}
			}
		}

		return Blocks.AIR.getBlockData();
	}

	public boolean isEmpty(BlockPosition var1) {
		return this.getType(var1).getBlock().getMaterial() == Material.AIR;
	}

	// WindyPit start - stable-read support for async path searches
	/**
	 * Captures the block-change generation of every chunk this cache can read.
	 *
	 * <p>Called on the primary thread immediately before the cache is handed to a worker. The
	 * worker re-reads the same generations through {@link #matchesGeneration(int[])} after the
	 * search: an unchanged sum means no block in the searched area moved while the search ran, so
	 * the resulting path is still valid. A mismatch discards the path instead of publishing a
	 * stale one.
	 */
	public int[] captureGeneration() {
		final int[] generations = new int[this.c.length * (this.c.length == 0 ? 0 : this.c[0].length)];
		int index = 0;
		for (int x = 0; x < this.c.length; x++) {
			for (int z = 0; z < this.c[x].length; z++) {
				final Chunk chunk = this.c[x][z];
				generations[index++] = chunk == null ? 0 : chunk.getBlockChangeGeneration();
			}
		}
		return generations;
	}

	/** Re-reads the same chunks; true when no block changed since {@link #captureGeneration()}. */
	public boolean matchesGeneration(int[] generations) {
		if (generations == null) {
			return false;
		}
		int index = 0;
		for (int x = 0; x < this.c.length; x++) {
			for (int z = 0; z < this.c[x].length; z++) {
				if (index >= generations.length) {
					return false;
				}
				final Chunk chunk = this.c[x][z];
				final int current = chunk == null ? 0 : chunk.getBlockChangeGeneration();
				if (current != generations[index++]) {
					return false;
				}
			}
		}
		return index == generations.length;
	}
	// WindyPit end

	public int getBlockPower(BlockPosition var1, EnumDirection var2) {
		IBlockData var3 = this.getType(var1);
		return var3.getBlock().b(this, var1, var3, var2);
	}
}