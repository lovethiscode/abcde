package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.screens.basescreen.BaseScreen
import kotlin.math.roundToInt
import kotlin.math.atan2
import kotlin.math.sqrt

/** Approved A composition, using the game's shared palette and existing bitmap font. */
internal class DiplomacyScreenStyle(private val textScale: Float = 1f) {
    companion object {
        fun forScreen(screen: BaseScreen): DiplomacyScreenStyle {
            val pointWidth = screen.stage.width / (UiNavigation.minimumTouchSize() / 44f)
            return DiplomacyScreenStyle(if (pointWidth >= 1100f) 1.3f else 1f)
        }
    }
    val unit = UiNavigation.minimumTouchSize() / 44f
    val background = ExpeditionBlueTheme.background
    val panel = ExpeditionBlueTheme.panel
    val raised = ExpeditionBlueTheme.raised
    val text = ExpeditionBlueTheme.text
    val muted = ExpeditionBlueTheme.muted
    val gold = ExpeditionBlueTheme.gold
    val positive = ExpeditionBlueTheme.success
    val danger = ExpeditionBlueTheme.danger
    fun px(n: Float) = n * unit
    fun font(n: Int) = (n * unit * textScale).roundToInt().coerceAtLeast(1)
    fun label(value: String, size: Int = 14, color: Color = text, wrap: Boolean = false, hideIcons: Boolean = false) =
        value.toLabel(color, font(size), hideIcons = hideIcons).apply { this.wrap = wrap }
    fun surface(color: Color, radius: Float = 12f): BaseDrawable {
        val dot = ImageGetter.getWhiteDotDrawable().tint(color)
        val circle = ImageGetter.getCircleDrawable().tint(color)
        return object : BaseDrawable() {
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                val r = minOf(px(radius), width / 2, height / 2)
                circle.draw(batch, x, y, r*2, r*2)
                circle.draw(batch, x+width-r*2, y, r*2, r*2)
                circle.draw(batch, x, y+height-r*2, r*2, r*2)
                circle.draw(batch, x+width-r*2, y+height-r*2, r*2, r*2)
                dot.draw(batch, x+r, y, (width-r*2).coerceAtLeast(0f), height)
                dot.draw(batch, x, y+r, width, (height-r*2).coerceAtLeast(0f))
            }
        }
    }
    fun decorate(button: TextButton, primary: Boolean = false, ghost: Boolean = false, icon: DiplomacyIcon? = null): TextButton {
        val disabled = button.isDisabled
        val destructive = button.name?.startsWith("ui-declare-war:") == true || button.name == "ui-diplomacy-denounce"
        button.style = TextButton.TextButtonStyle(BaseScreen.skin.get(TextButton.TextButtonStyle::class.java)).apply {
            up = if (primary) surface(gold, 8f) else if (ghost) null else surface(raised, 8f)
            down = surface(if (primary) gold.cpy().mul(.9f,.9f,.9f,1f) else ExpeditionBlueTheme.hero, 8f)
            over = down; checked = surface(raised, 8f); this.disabled = surface(raised, 8f)
            fontColor = if (primary) ExpeditionBlueTheme.primaryInk else if(destructive) danger else text
            downFontColor = fontColor; overFontColor = fontColor; checkedFontColor = gold; disabledFontColor = muted
        }
        button.label.setFontSize(font(14)); button.label.setEllipsis(true)
        button.pad(px(8f), px(12f), px(8f), px(12f))
        if(icon != null) {
            button.clearChildren()
            button.add(icon.image(if(primary) ExpeditionBlueTheme.primaryInk else text)).size(px(18f)).padRight(px(8f))
            button.add(button.label).minWidth(0f)
        }
        button.isDisabled = disabled
        return button
    }

    fun decorate(button: IconTextButton): IconTextButton {
        val disabled = button.isDisabled
        button.style = Button.ButtonStyle(button.style).apply {
            up = surface(raised, 8f)
            down = surface(ExpeditionBlueTheme.hero, 8f)
            over = down
            checked = up
            this.disabled = up
        }
        button.label.setFontSize(font(14))
        button.label.wrap = true
        button.label.setEllipsis(false)
        if (disabled) button.label.color = muted
        else if (button.label.color == Color.WHITE) button.label.color = text
        button.labelCell.pad(0f).growX().minWidth(0f)
        button.iconCell.size(if (button.icon == null) 0f else px(18f)).padRight(if (button.icon == null) 0f else px(8f))
        button.pad(px(8f), px(12f), px(8f), px(12f))
        button.isDisabled = disabled
        return button
    }
}

/** Point sizes are independent of the game's virtual resolution and text scale. */
class DiplomacyScreenLayout(val width: Float, val height: Float) {
    val narrow = width <= 700f
    val wide = width >= 1100f
    val padding = if (wide) 24f else 12f
    val gap = if (wide) 24f else 16f
    val rosterWidth = if (wide) 264f else 196f
    val detailWidth = if (narrow) width-padding*2 else width-padding*2-rosterWidth-gap
    val contentPadding = if (wide) 28f else 18f
    val columnWidth = if (narrow) detailWidth-contentPadding*2 else (detailWidth-contentPadding*2-20f)/2
    companion object {
        fun influencePosition(influence: Float) = ((influence+30f)/90f).coerceIn(0f, 1f)
    }
}

internal enum class DiplomacyIcon(vararg val lines: FloatArray) {
    Gift(floatArrayOf(3f,8f,21f,8f),floatArrayOf(3f,8f,3f,12f),floatArrayOf(21f,8f,21f,12f),floatArrayOf(3f,12f,21f,12f),floatArrayOf(5f,12f,5f,21f),floatArrayOf(19f,12f,19f,21f),floatArrayOf(5f,21f,19f,21f),floatArrayOf(12f,8f,12f,21f),floatArrayOf(12f,8f,7f,3f),floatArrayOf(7f,3f,5f,5f),floatArrayOf(5f,5f,12f,8f),floatArrayOf(12f,8f,17f,3f),floatArrayOf(17f,3f,19f,5f),floatArrayOf(19f,5f,12f,8f)),
    Down(floatArrayOf(6f,9f,12f,15f),floatArrayOf(12f,15f,18f,9f)),
    Trade(floatArrayOf(3f,7f,20f,7f),floatArrayOf(16f,3f,20f,7f),floatArrayOf(20f,7f,16f,11f),floatArrayOf(21f,17f,4f,17f),floatArrayOf(8f,13f,4f,17f),floatArrayOf(4f,17f,8f,21f)),
    Map(floatArrayOf(3f,5f,9f,3f),floatArrayOf(9f,3f,15f,5f),floatArrayOf(15f,5f,21f,3f),floatArrayOf(3f,5f,3f,21f),floatArrayOf(9f,3f,9f,19f),floatArrayOf(15f,5f,15f,21f),floatArrayOf(21f,3f,21f,19f),floatArrayOf(3f,21f,9f,19f),floatArrayOf(9f,19f,15f,21f),floatArrayOf(15f,21f,21f,19f));
    fun image(tint: Color)=Image(object:BaseDrawable(){
        override fun draw(batch:Batch,x:Float,y:Float,width:Float,height:Float){
            val previous=batch.packedColor;batch.setColor(tint.r,tint.g,tint.b,batch.color.a)
            val region=ImageGetter.getWhiteDotDrawable().region;val stroke=width*1.65f/24f
            for(line in lines){val dx=(line[2]-line[0])*width/24f;val dy=(line[1]-line[3])*height/24f
                batch.draw(region,x+line[0]*width/24f,y+(24f-line[1])*height/24f-stroke/2,0f,stroke/2,
                    sqrt(dx*dx+dy*dy),stroke,1f,1f,atan2(dy,dx)*180f/Math.PI.toFloat())}
            batch.packedColor=previous
        }
    })
}
