package com.agentdeck.ui

import android.graphics.*
import kotlin.math.cos
import kotlin.math.sin

/** Cached, soft-edged light fields moving underneath glass, independent of frame rate. */
class FrostedAura {
    private val colors=intArrayOf(Color.rgb(72,212,233),Color.rgb(149,108,237),Color.rgb(67,175,236))
    private val shaders=colors.map { color ->
        fun tint(alpha:Int)=Color.argb(alpha,Color.red(color),Color.green(color),Color.blue(color))
        RadialGradient(0f,0f,1f,intArrayOf(tint(115),tint(76),tint(25),tint(0)),
            floatArrayOf(0f,.30f,.68f,1f),Shader.TileMode.CLAMP)
    }
    private val matrix=Matrix()
    private val brush=Paint(Paint.ANTI_ALIAS_FLAG)
    fun draw(c:Canvas,bounds:RectF,now:Long) {
        // Continuous time avoids a visible reset; ambient movement takes about
        // a minute and a half per orbit rather than competing with message text.
        val t=now/1000.0
        shaders.forEachIndexed { i,shader ->
            val phase=i*2.1
            val x=bounds.left+bounds.width()*(.50f+sin(t*.0675+phase).toFloat()*.34f)
            val y=bounds.top+bounds.height()*(.64f+cos(t*.08+phase).toFloat()*.27f)
            val radius=bounds.height()*(.48f+sin(t*.05+phase).toFloat()*.06f)
            matrix.setScale(radius,radius);matrix.postTranslate(x,y)
            shader.setLocalMatrix(matrix);brush.shader=shader
            c.drawCircle(x,y,radius,brush)
        }
        brush.shader=null
    }
}
