package fr.inertialnav.iau.core.math

import kotlin.math.abs

/**
 * Matrice dense (row-major) minimaliste, suffisante pour le filtre de Kalman (15 états).
 * Les opérations renvoient de nouvelles matrices sauf mention contraire.
 */
class Matrix(val rows: Int, val cols: Int, val data: DoubleArray = DoubleArray(rows * cols)) {

    init {
        require(data.size == rows * cols) { "Taille de données incohérente" }
    }

    operator fun get(r: Int, c: Int): Double = data[r * cols + c]
    operator fun set(r: Int, c: Int, v: Double) {
        data[r * cols + c] = v
    }

    operator fun plus(o: Matrix): Matrix {
        requireSameShape(o)
        return Matrix(rows, cols, DoubleArray(data.size) { data[it] + o.data[it] })
    }

    operator fun minus(o: Matrix): Matrix {
        requireSameShape(o)
        return Matrix(rows, cols, DoubleArray(data.size) { data[it] - o.data[it] })
    }

    operator fun times(s: Double) = Matrix(rows, cols, DoubleArray(data.size) { data[it] * s })

    operator fun times(o: Matrix): Matrix {
        require(cols == o.rows) { "Dimensions incompatibles ${rows}x$cols * ${o.rows}x${o.cols}" }
        val out = Matrix(rows, o.cols)
        for (i in 0 until rows) {
            val ri = i * cols
            val ro = i * o.cols
            for (k in 0 until cols) {
                val a = data[ri + k]
                if (a == 0.0) continue
                val rk = k * o.cols
                for (j in 0 until o.cols) out.data[ro + j] += a * o.data[rk + j]
            }
        }
        return out
    }

    operator fun times(v: DoubleArray): DoubleArray {
        require(cols == v.size)
        return DoubleArray(rows) { i ->
            var s = 0.0
            for (k in 0 until cols) s += data[i * cols + k] * v[k]
            s
        }
    }

    operator fun times(v: Vec3): Vec3 {
        require(rows == 3 && cols == 3)
        return Vec3(
            data[0] * v.x + data[1] * v.y + data[2] * v.z,
            data[3] * v.x + data[4] * v.y + data[5] * v.z,
            data[6] * v.x + data[7] * v.y + data[8] * v.z,
        )
    }

    fun transpose(): Matrix {
        val out = Matrix(cols, rows)
        for (i in 0 until rows) for (j in 0 until cols) out.data[j * rows + i] = data[i * cols + j]
        return out
    }

    fun copy() = Matrix(rows, cols, data.copyOf())

    fun block(r: Int, c: Int, nr: Int, nc: Int): Matrix {
        val out = Matrix(nr, nc)
        for (i in 0 until nr) for (j in 0 until nc) out[i, j] = this[r + i, c + j]
        return out
    }

    /** Copie [m] dans cette matrice à partir de (r, c). En place. */
    fun setBlock(r: Int, c: Int, m: Matrix) {
        for (i in 0 until m.rows) for (j in 0 until m.cols) this[r + i, c + j] = m[i, j]
    }

    /** Remplace la matrice par (A + Aᵀ)/2. En place. */
    fun symmetrize() {
        require(rows == cols)
        for (i in 0 until rows) for (j in i + 1 until cols) {
            val m = 0.5 * (this[i, j] + this[j, i])
            this[i, j] = m
            this[j, i] = m
        }
    }

    /** Inverse par Gauss-Jordan avec pivot partiel. */
    fun inverse(): Matrix {
        require(rows == cols) { "Matrice non carrée" }
        val n = rows
        val a = copy()
        val inv = identity(n)
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r, col]) > abs(a[pivot, col])) pivot = r
            val pv = a[pivot, col]
            if (abs(pv) < 1e-300) throw ArithmeticException("Matrice singulière")
            if (pivot != col) {
                a.swapRows(pivot, col)
                inv.swapRows(pivot, col)
            }
            val s = 1.0 / pv
            for (j in 0 until n) {
                a[col, j] *= s
                inv[col, j] *= s
            }
            for (r in 0 until n) {
                if (r == col) continue
                val f = a[r, col]
                if (f == 0.0) continue
                for (j in 0 until n) {
                    a[r, j] -= f * a[col, j]
                    inv[r, j] -= f * inv[col, j]
                }
            }
        }
        return inv
    }

    private fun swapRows(r1: Int, r2: Int) {
        for (j in 0 until cols) {
            val t = this[r1, j]
            this[r1, j] = this[r2, j]
            this[r2, j] = t
        }
    }

    private fun requireSameShape(o: Matrix) =
        require(rows == o.rows && cols == o.cols) { "Dimensions différentes" }

    override fun toString(): String = buildString {
        for (i in 0 until rows) {
            append((0 until cols).joinToString(", ", "[", "]") { "%.4g".format(this@Matrix[i, it]) })
            if (i < rows - 1) append('\n')
        }
    }

    companion object {
        fun identity(n: Int) = Matrix(n, n).apply { for (i in 0 until n) this[i, i] = 1.0 }

        fun diag(vararg v: Double) = Matrix(v.size, v.size).apply { v.forEachIndexed { i, d -> this[i, i] = d } }

        /** Matrice antisymétrique [v×] telle que [v×]·u = v × u. */
        fun skew(v: Vec3) = Matrix(
            3, 3, doubleArrayOf(
                0.0, -v.z, v.y,
                v.z, 0.0, -v.x,
                -v.y, v.x, 0.0,
            )
        )
    }
}
