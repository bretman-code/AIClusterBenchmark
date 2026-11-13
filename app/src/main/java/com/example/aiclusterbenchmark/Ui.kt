package com.example.aiclusterbenchmark

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.*

internal object Ui {
    val ink=Color.rgb(234,242,250)
    val muted=Color.rgb(151,169,189)
    private val teal=Color.rgb(94,231,202)
    fun dp(a:Activity,n:Int)=(n*a.resources.displayMetrics.density).toInt()
    private fun shape(a:Activity,color:Int,stroke:Int?=null)=GradientDrawable().apply {
        setColor(color); cornerRadius=dp(a,20).toFloat()
        stroke?.let { setStroke(dp(a,1),it) }
    }
    fun text(a:Activity,value:String,size:Float=15f,color:Int=ink)=TextView(a).apply {
        text=value; textSize=size; setTextColor(color); setLineSpacing(dp(a,3).toFloat(),1f)
        if(size>=20) typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    }
    fun page(a:Activity,title:String,subtitle:String):LinearLayout {
        a.window.statusBarColor=Color.rgb(10,17,29)
        a.window.navigationBarColor=Color.rgb(10,17,29)
        a.window.decorView.systemUiVisibility=0
        val column=LinearLayout(a).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(a,20),dp(a,24),dp(a,20),dp(a,32))
        }
        val scroll=ScrollView(a).apply {
            isFillViewport=true
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(10,17,29),Color.rgb(17,30,44)))
            addView(column)
        }
        a.setContentView(scroll)
        column.addView(text(a,"AI CLUSTER  /  BENCHMARK",11f,teal).apply { letterSpacing=.15f })
        gap(a,column,14)
        column.addView(text(a,title,30f))
        gap(a,column,8)
        column.addView(text(a,subtitle,14f,muted))
        gap(a,column,24)
        return column
    }
    fun gap(a:Activity,parent:LinearLayout,size:Int=12) { parent.addView(View(a),LinearLayout.LayoutParams(1,dp(a,size))) }
    fun card(a:Activity,parent:LinearLayout,title:String,description:String?=null):LinearLayout {
        val box=LinearLayout(a).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(a,18),dp(a,18),dp(a,18),dp(a,18))
            background=shape(a,Color.rgb(23,37,54),Color.rgb(40,60,78))
        }
        parent.addView(box,LinearLayout.LayoutParams(-1,-2))
        box.addView(text(a,title,20f))
        if(description!=null) { gap(a,box,6); box.addView(text(a,description,14f,muted)) }
        gap(a,box,14)
        return box
    }
    fun button(a:Activity,title:String,primary:Boolean=false)=Button(a).apply {
        text=title; isAllCaps=false; textSize=15f
        typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        minHeight=dp(a,54); minimumHeight=dp(a,54)
        setPadding(dp(a,14),dp(a,10),dp(a,14),dp(a,10))
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(muted,if(primary) Color.rgb(8,33,34) else ink)))
        background=StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled),shape(a,Color.rgb(35,48,62)))
            addState(intArrayOf(android.R.attr.state_pressed),shape(a,if(primary) Color.rgb(63,191,167) else Color.rgb(45,66,85)))
            addState(intArrayOf(),shape(a,if(primary) teal else Color.rgb(35,53,73)))
        }
        layoutParams=LinearLayout.LayoutParams(-1,-2)
    }
}
