package com.fnreport.mapper

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

typealias Column = Pair<String, (Any?) -> Any?>
typealias Coordinates = Pair<Int, Int>
typealias Decoder = Triple<Column, Coordinates, (ByteArray, Coordinates) -> Any?>


interface RowStore<T> {

    /**
     * seek to row
     */

    operator fun invoke(row: Int): T

    val size: Int
}


interface FixedLength<T> : Indexed<T> {
    val recordLen: Int
}
/*
interface ColumnAccess<T> {
    operator fun get(vararg c: Int): RowStore<T>
    val columns: List<Column>
}*/

abstract class FileAccess(open val filename: String) : Closeable

/*

interface IDataFrame<T> : RowStore<T>, ColumnAccess<T>
*/


//todo: map multiple segments for a very big file
open class MappedFile(
        filename: String,
        randomAccessFile: RandomAccessFile = RandomAccessFile(filename, "r"),
        channel: FileChannel = randomAccessFile.channel,
        length: Long = randomAccessFile.length(),
        val mappedByteBuffer: MappedByteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, length),
        override val size: Int = mappedByteBuffer.limit()
) : RowStore<ByteBuffer>, FileAccess(
        filename), Closeable by randomAccessFile {
    override fun invoke(row: Int): ByteBuffer = mappedByteBuffer.apply { position(row) }.slice()
}

/**
One-dimensional ndarray with axis labels (including time series).

Labels need not be unique but must be a hashable type. The object supports both integer- and label-based indexing and provides a host of methods for performing operations involving the index. Statistical methods from ndarray have been overridden to automatically exclude missing data (currently represented as NaN).

Operations between Series (+, -, /, , *) align values based on their associated index values– they need not be the same length. The result index will be the sorted union of the two indexes.
 */
interface Indexed<T> {
    operator fun get(vararg rows: Int): T
    operator fun get(rows: IntRange): T = get(* rows.toList().toIntArray())

}
//todo: map multiple segments for a very big file

abstract class LineBuffer : Indexed<Flow<ByteBuffer>>
class FixedRecordLengthFile(filename: String, origin: MappedFile = MappedFile(filename)) : Closeable by origin, FixedRecordLengthBuffer(buf = origin.mappedByteBuffer)

open class FixedRecordLengthBuffer(val buf: ByteBuffer,
                                   override val recordLen: Int = buf.run {
                                       var c = 0.toByte()
                                       do c = get() while (c != '\n'.toByte())
                                       position()
                                   },

                                   val size: Int = (recordLen / buf.limit())
) : LineBuffer(),
        FixedLength<Flow<ByteBuffer>> {
    override fun get(vararg rows: Int) = rows.map { buf.position(recordLen * it).slice().apply { limit(recordLen) } }.asFlow()

}

class VariableRecordLengthFile(filename: String, origin: MappedFile = MappedFile(filename)) : Closeable by origin, VariableRecordLengthBuffer(buf = origin.mappedByteBuffer)

open class VariableRecordLengthBuffer(val buf: ByteBuffer, val eor: Char = '\n', val index: IntArray = buf.duplicate().clear().run {
    val list = mutableListOf<Int>(position())
    var c = 0.toChar()
    while (hasRemaining()) {
        c = get().toChar()
        if (hasRemaining() && c == eor)
            list += position()
    }
    list.toIntArray()
}
) : LineBuffer() {
    override fun get(vararg rows: Int) =
            rows.map { row: Int ->
                val begin = index[row]
                buf.position(begin).slice().also { slice ->
                    val last = index.size - 1
                    if (row != last) {
                        val nextRecord = index[row + 1]
                        val sliceLen = nextRecord - begin - 1
                        slice.limit(sliceLen)
                    }
                }
            }.asFlow()
}


