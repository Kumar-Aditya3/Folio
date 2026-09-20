package com.folio.reader.ml

/**
 * Interprets a raw ONNX tensor as an [OnnxOutput].
 *
 * Lives in `commonJvm` rather than in either `actual` file because both platforms need the
 * same shape handling, and `internal` declarations in `desktopMain` are not visible to
 * `androidMain` (different compilations).
 *
 * Handles both export styles seen in the wild:
 * - `[batch, seq, hidden]` — last hidden state, the common sentence-transformers export
 * - `[batch, hidden]` — an export that already pooled, in which case [OnnxOutput.isPooled]
 *   is true and the caller must not pool again
 */
internal fun interpretOnnxOutput(values: FloatArray, shape: LongArray, batchSize: Int): OnnxOutput =
    when (shape.size) {
        3 -> OnnxOutput(
            values = values,
            batchSize = maxOf(shape[0].toInt(), batchSize),
            seqLength = shape[1].toInt(),
            hiddenSize = shape[2].toInt(),
        )
        2 -> OnnxOutput(
            values = values,
            batchSize = maxOf(shape[0].toInt(), batchSize),
            seqLength = 1,
            hiddenSize = shape[1].toInt(),
        )
        else -> throw OnnxUnavailableException(
            "Unexpected model output rank ${shape.size}: ${shape.toList()}"
        )
    }
