package de.gnampf.syncusgnampfus;

import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

public final class FCSolver
{
	private FCSolver() {}

	private static final long[] IV = {
		0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
		0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L };
	private static final int[][] SIGMA = {
		{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15},
		{14,10,4,8,9,15,13,6,1,12,0,2,11,7,5,3},
		{11,8,12,0,5,2,15,13,10,14,3,6,7,1,9,4},
		{7,9,3,1,13,12,11,14,2,6,5,10,4,0,15,8},
		{9,0,5,7,2,4,10,15,14,1,11,12,6,8,3,13},
		{2,12,6,10,0,11,8,3,4,13,7,5,15,14,1,9},
		{12,5,1,15,14,13,4,10,0,7,6,3,9,2,8,11},
		{13,11,7,14,12,1,3,9,5,0,15,4,8,6,2,10},
		{6,15,14,9,11,3,0,8,12,2,13,7,1,4,10,5},
		{10,2,8,4,7,6,1,5,15,11,9,14,3,12,13,0},
		{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15},
		{14,10,4,8,9,15,13,6,1,12,0,2,11,7,5,3} };

	private static long blake2b256First32(long[] m)
	{
		long v0=IV[0]^0x01010020L, v1=IV[1], v2=IV[2], v3=IV[3], v4=IV[4], v5=IV[5], v6=IV[6], v7=IV[7];
		long v8=IV[0], v9=IV[1], v10=IV[2], v11=IV[3], v12=IV[4]^128L, v13=IV[5], v14=IV[6]^0xFFFFFFFFFFFFFFFFL, v15=IV[7];
		long h0=v0;
		for (int r=0;r<12;r++)
		{
			int[] s=SIGMA[r];
			v0=v0+v4+m[s[0]]; v12=Long.rotateRight(v12^v0,32); v8=v8+v12; v4=Long.rotateRight(v4^v8,24); v0=v0+v4+m[s[1]]; v12=Long.rotateRight(v12^v0,16); v8=v8+v12; v4=Long.rotateRight(v4^v8,63);
			v1=v1+v5+m[s[2]]; v13=Long.rotateRight(v13^v1,32); v9=v9+v13; v5=Long.rotateRight(v5^v9,24); v1=v1+v5+m[s[3]]; v13=Long.rotateRight(v13^v1,16); v9=v9+v13; v5=Long.rotateRight(v5^v9,63);
			v2=v2+v6+m[s[4]]; v14=Long.rotateRight(v14^v2,32); v10=v10+v14; v6=Long.rotateRight(v6^v10,24); v2=v2+v6+m[s[5]]; v14=Long.rotateRight(v14^v2,16); v10=v10+v14; v6=Long.rotateRight(v6^v10,63);
			v3=v3+v7+m[s[6]]; v15=Long.rotateRight(v15^v3,32); v11=v11+v15; v7=Long.rotateRight(v7^v11,24); v3=v3+v7+m[s[7]]; v15=Long.rotateRight(v15^v3,16); v11=v11+v15; v7=Long.rotateRight(v7^v11,63);
			v0=v0+v5+m[s[8]]; v15=Long.rotateRight(v15^v0,32); v10=v10+v15; v5=Long.rotateRight(v5^v10,24); v0=v0+v5+m[s[9]]; v15=Long.rotateRight(v15^v0,16); v10=v10+v15; v5=Long.rotateRight(v5^v10,63);
			v1=v1+v6+m[s[10]]; v12=Long.rotateRight(v12^v1,32); v11=v11+v12; v6=Long.rotateRight(v6^v11,24); v1=v1+v6+m[s[11]]; v12=Long.rotateRight(v12^v1,16); v11=v11+v12; v6=Long.rotateRight(v6^v11,63);
			v2=v2+v7+m[s[12]]; v13=Long.rotateRight(v13^v2,32); v8=v8+v13; v7=Long.rotateRight(v7^v8,24); v2=v2+v7+m[s[13]]; v13=Long.rotateRight(v13^v2,16); v8=v8+v13; v7=Long.rotateRight(v7^v8,63);
			v3=v3+v4+m[s[14]]; v14=Long.rotateRight(v14^v3,32); v9=v9+v14; v4=Long.rotateRight(v4^v9,24); v3=v3+v4+m[s[15]]; v14=Long.rotateRight(v14^v3,16); v9=v9+v14; v4=Long.rotateRight(v4^v9,63);
		}
		return (h0 ^ v0 ^ v8) & 0xFFFFFFFFL;
	}

	private static long le64(byte[] b, int off)
	{
		long r=0; for (int i=0;i<8;i++) r |= (b[off+i]&0xFFL)<<(8*i); return r;
	}

	public static String solve(String puzzle) throws Exception
	{
		String[] parts = puzzle.split("\\.");
		final String signature = parts[0];
		final String base64 = parts[1];
		final byte[] buf = Base64.getDecoder().decode(base64);
		final int n = buf[14] & 0xFF;
		final int difficulty = buf[15] & 0xFF;
		final long threshold = (long) Math.floor(Math.pow(2, (255.999 - difficulty) / 8.0));

		final byte[] solutions = new byte[8 * n];
		long start = System.currentTimeMillis();

		int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
		var pool = Executors.newFixedThreadPool(threads);
		var tasks = new ArrayList<Callable<Void>>();
		for (int idx = 0; idx < n; idx++)
		{
			final int index = idx;
			tasks.add(() -> {
				byte[] in = new byte[128];
				System.arraycopy(buf, 0, in, 0, buf.length);
				in[120] = (byte) index;
				long[] m = new long[16];
				for (int i = 0; i < 16; i++) m[i] = le64(in, i * 8);
				for (int outer = 0; outer < 256; outer++)
				{
					long base = ((long) outer) << 24;
					long low = le64(in, 120) & 0x0000000000FFFFFFL | base;
					for (long nonce = 0; nonce <= 0xFFFFFFFFL; nonce++)
					{
						m[15] = low | (nonce << 32);
						if (blake2b256First32(m) < threshold)
						{
							in[123] = (byte) outer;
							in[124] = (byte) nonce; in[125] = (byte) (nonce >> 8);
							in[126] = (byte) (nonce >> 16); in[127] = (byte) (nonce >> 24);
							System.arraycopy(in, 120, solutions, 8 * index, 8);
							return null;
						}
					}
				}
				throw new IllegalStateException("Keine Loesung fuer Teil " + index);
			});
		}
		try
		{
			for (var f : pool.invokeAll(tasks)) f.get();
		}
		finally
		{
			pool.shutdownNow();
		}

		int sec = (int) Math.min(65535, (System.currentTimeMillis() - start) / 1000);
		byte[] diag = new byte[]{ 2, (byte) (sec >> 8), (byte) sec };
		var b64 = Base64.getEncoder();
		return signature + "." + base64 + "." + b64.encodeToString(solutions) + "." + b64.encodeToString(diag);
	}
}
