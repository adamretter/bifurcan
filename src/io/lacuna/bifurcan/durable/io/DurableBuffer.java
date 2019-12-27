package io.lacuna.bifurcan.durable.io;

import io.lacuna.bifurcan.DurableInput;
import io.lacuna.bifurcan.DurableOutput;
import io.lacuna.bifurcan.LinearList;
import io.lacuna.bifurcan.Lists;
import io.lacuna.bifurcan.durable.BlockPrefix;
import io.lacuna.bifurcan.durable.Util;
import io.lacuna.bifurcan.durable.allocator.GenerationalAllocator;
import io.lacuna.bifurcan.durable.allocator.IBuffer;
import io.lacuna.bifurcan.durable.allocator.SlabAllocator;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.function.Consumer;

public class DurableBuffer implements DurableOutput {

  private static final int BUFFER_MERGE_THRESHOLD = GenerationalAllocator.SPILL_THRESHOLD;

  private final LinearList<IBuffer> flushed = new LinearList<>();
  private long flushedBytes = 0;

  private IBuffer curr;
  private ByteBuffer bytes;

  private boolean isOpen = true;

  public DurableBuffer() {
    this.curr = allocate(bufferSize());
    this.bytes = curr.bytes();
  }

  public static void flushTo(DurableOutput out, Consumer<DurableBuffer> body) {
    DurableBuffer acc = new DurableBuffer();
    body.accept(acc);
    acc.flushTo(out);
  }

  public static void flushTo(DurableOutput out, BlockPrefix.BlockType type, Consumer<DurableBuffer> body) {
    DurableBuffer acc = new DurableBuffer();
    body.accept(acc);
    acc.flushTo(out, type);
  }

  /**
   * Writes the contents of the accumulator to `out`, and frees the associated buffers.
   */
  public void flushTo(DurableOutput out) {
    close();
    out.append(flushed);
  }

  public DurableInput toInput() {
    close();
    return DurableInput.from(flushed.stream().map(IBuffer::toInput).collect(Lists.linearCollector()));
  }

  public void flushTo(DurableOutput out, BlockPrefix.BlockType type) {
    close();
    BlockPrefix p = new BlockPrefix(written(), type);
    p.encode(out);
    flushTo(out);
  }

  public void free() {
    close();
    flushed.forEach(IBuffer::free);
  }

  @Override
  public void close() {
    if (isOpen) {
      isOpen = false;
      flushCurrentBuffer(true);
      bytes = null;
    }
  }

  @Override
  public void flush() {
  }

  @Override
  public long written() {
    return flushedBytes + (bytes != null ? bytes.position() : 0);
  }

  @Override
  public int write(ByteBuffer src) {
    int n = src.remaining();

    Util.transfer(src, bytes);
    while (src.remaining() > 0) {
      Util.transfer(src, ensureCapacity(src.remaining()));
    }

    return n;
  }

  @Override
  public void transferFrom(DurableInput in) {
    while (in.hasRemaining()) {
      in.read(this.bytes);
      ensureCapacity((int) Math.min(in.remaining(), Integer.MAX_VALUE));
    }
  }

  @Override
  public void append(Iterable<IBuffer> buffers) {
    Iterator<IBuffer> it = buffers.iterator();
    while (it.hasNext()) {
      IBuffer buf = it.next();
      if (buf.size() < BUFFER_MERGE_THRESHOLD) {
        transferFrom(buf.toInput());
        buf.free();
      } else {
        flushCurrentBuffer(false);
        appendBuffer(buf);
        it.forEachRemaining(this::appendBuffer);
      }
    }
  }

  @Override
  public void writeByte(int v) {
    ensureCapacity(1).put((byte) v);
  }

  @Override
  public void writeShort(int v) {
    ensureCapacity(2).putShort((short) v);
  }

  @Override
  public void writeChar(int v) {
    ensureCapacity(2).putChar((char) v);
  }

  @Override
  public void writeInt(int v) {
    ensureCapacity(4).putInt(v);
  }

  @Override
  public void writeLong(long v) {
    ensureCapacity(8).putLong(v);
  }

  @Override
  public void writeFloat(float v) {
    ensureCapacity(4).putFloat(v);
  }

  @Override
  public void writeDouble(double v) {
    ensureCapacity(8).putDouble(v);
  }

  //

  private static final int MIN_BUFFER_SIZE = 4 << 10;
  private static final int MAX_BUFFER_SIZE = 16 << 20;

  private int bufferSize() {
    return curr == null ? MIN_BUFFER_SIZE : (int) Math.min(MAX_BUFFER_SIZE, written() / 4);
  }

  private IBuffer allocate(int n) {
//    return SlabAllocator.allocate(n);
    return GenerationalAllocator.allocate(n);
  }

  private void appendBuffer(IBuffer b) {
    flushedBytes += b.size();
    flushed.addLast(b);
  }

  private void flushCurrentBuffer(boolean isClosed) {
    appendBuffer(curr.close(bytes.position()));
    if (!isClosed) {
      curr = allocate(bufferSize());
      bytes = curr.bytes();
    }
  }

  private ByteBuffer ensureCapacity(int n) {
    if (n > bytes.remaining()) {
      flushCurrentBuffer(false);
    }
    return bytes;
  }
}