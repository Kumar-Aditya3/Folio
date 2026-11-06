package com.folio.reader.tumble

import kotlin.math.*

/** Paper and facial poses share one timeline so expression never floats off the ribbon. */
internal data class BookmarkFrame(
    val bend: Float = 0f,
    val lean: Float = 0f,
    val lift: Float = 0f,
    val width: Float = 1f,
    val height: Float = 1f,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    val eyes: Float = 1f,
    val happyEyes: Float = 0f,
    val leftBrow: Float = 0f,
    val rightBrow: Float = 0f,
    val smile: Float = .08f,
    val mouthOpen: Float = 0f,
    val curl: Float = .04f,
)

internal fun bookmarkFrame(action: String, progress: Float, pose: String = "watch", seconds: Float = 0f,
    motion: Boolean = true, gazeX: Float? = null, gazeY: Float? = null): BookmarkFrame {
    val t=if(progress.isFinite()) progress.coerceIn(0f,1f) else 0f
    val clock=if(motion && seconds.isFinite()) seconds else 0f
    fun smooth(value:Float):Float {val x=value.coerceIn(0f,1f);return x*x*(3-2*x)}
    val amount=if(!motion) 1f else smooth(t/.16f)*(1-smooth((t-.70f)/.30f))
    val sleepy=pose=="sleep"
    val reading=pose=="read"
    val base=BookmarkFrame(
        bend=if(motion) sin(clock*2*PI.toFloat()/6.5f)*.028f else 0f,
        lean=if(motion) sin(clock*2*PI.toFloat()/13f)*1.1f else 0f,
        eyes=if(sleepy) .08f else if(reading) .85f else 1f,
        gazeY=if(reading) .35f else 0f,
        smile=if(sleepy) 0f else .08f,
    )
    val target=when(action) {
        "greet", "wave" -> BookmarkFrame(bend=-.09f,lean=-3f,lift=.012f,gazeX=.3f,eyes=1.05f,leftBrow=.015f,rightBrow=.01f,smile=.4f,curl=.14f)
        "inspect", "peek", "think" -> BookmarkFrame(bend=.12f,lean=3.5f,gazeX=.65f,gazeY=-.15f,eyes=1.1f,leftBrow=.045f,rightBrow=.005f,smile=.12f,curl=.18f)
        "read", "bookmark" -> BookmarkFrame(bend=.065f,lean=2f,gazeY=.65f,eyes=.72f,leftBrow=-.01f,rightBrow=-.01f,smile=.18f,curl=.12f)
        "celebrate", "proud" -> BookmarkFrame(bend=if(motion) sin(t*PI.toFloat()*4)*.1f else -.08f,
            lean=if(motion) sin(t*PI.toFloat()*2)*4 else -4f,lift=if(motion) abs(sin(t*PI.toFloat()*2))*.025f else .018f,
            width=1.025f,height=.985f,eyes=.8f,happyEyes=.65f,leftBrow=.018f,rightBrow=.018f,smile=.45f,mouthOpen=.08f,curl=.2f)
        "pet", "relax" -> BookmarkFrame(bend=-.07f,lean=-2f,width=1.015f,height=.99f,eyes=.65f,happyEyes=.55f,smile=.3f,curl=.12f)
        "stretch", "roll" -> BookmarkFrame(bend=-.11f,lean=-3f,width=.96f,height=1.04f,eyes=.35f,happyEyes=.55f,smile=.2f,curl=.22f)
        "surprised" -> BookmarkFrame(bend=-.085f,lean=-4f,lift=.012f,width=.985f,height=1.015f,eyes=1.16f,leftBrow=.035f,rightBrow=.035f,smile=.04f,mouthOpen=.15f,curl=.16f)
        "look" -> BookmarkFrame(bend=-.095f,lean=-2.5f,gazeX=-.75f,gazeY=-.25f,eyes=1.025f,leftBrow=.018f,smile=.15f,curl=.14f)
        "settle", "yawn" -> BookmarkFrame(bend=.07f,lean=2f,width=1.015f,height=.985f,eyes=.12f,smile=0f,mouthOpen=if(action=="yawn") .1f else 0f,curl=.18f)
        else -> base
    }
    fun blend(a:Float,b:Float)=a+(b-a)*amount
    var eyes=blend(base.eyes,target.eyes)
    // A smooth blink, followed occasionally by a smaller second blink.
    if(motion && !sleepy) {
        val cycle=((clock%13f)+13f)%13f
        fun blink(center:Float,width:Float)= (1-abs(cycle-center)/width).coerceIn(0f,1f)
        eyes*=1-max(blink(4.25f,.13f),max(blink(10.3f,.14f),blink(10.65f,.10f)))
    }
    return BookmarkFrame(
        bend=blend(base.bend,target.bend),lean=blend(base.lean,target.lean),lift=target.lift*amount,
        width=blend(1f,target.width),height=blend(1f,target.height),
        gazeX=(gazeX?.takeIf {it.isFinite()}?.coerceIn(-1f,1f) ?: blend(base.gazeX,target.gazeX)),
        gazeY=(gazeY?.takeIf {it.isFinite()}?.coerceIn(-1f,1f) ?: blend(base.gazeY,target.gazeY)),
        eyes=eyes,happyEyes=target.happyEyes*amount,leftBrow=target.leftBrow*amount,rightBrow=target.rightBrow*amount,
        smile=blend(base.smile,target.smile),mouthOpen=target.mouthOpen*amount,curl=blend(base.curl,target.curl),
    )
}
