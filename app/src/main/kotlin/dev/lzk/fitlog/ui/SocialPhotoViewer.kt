package dev.lzk.fitlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lzk.fitlog.domain.SocialRepository

@Composable internal fun SocialPhotoViewer(repository:SocialRepository,paths:List<String>,initial:String,onClose:()->Unit) {
    var index by remember(paths,initial){mutableIntStateOf(paths.indexOf(initial).coerceAtLeast(0))}
    var scale by remember(index){mutableFloatStateOf(1f)}
    var x by remember(index){mutableFloatStateOf(0f)}
    var y by remember(index){mutableFloatStateOf(0f)}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().testTag("photo-viewer")) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick=onClose){Text("关闭大图",color=Color.White)}
                Spacer(Modifier.weight(1f))
                Text((index+1).toString()+" / "+paths.size,color=Color.White,modifier=Modifier.padding(16.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(index) {
                detectTransformGestures {_,pan,zoom,_->
                    scale=(scale*zoom).coerceIn(1f,5f)
                    val limitX=size.width*(scale-1)/2
                    val limitY=size.height*(scale-1)/2
                    x=(x+pan.x).coerceIn(-limitX,limitX);y=(y+pan.y).coerceIn(-limitY,limitY)
                }
            },contentAlignment=Alignment.Center) {
                key(index) {
                    SocialImage(repository,paths[index],Modifier.fillMaxSize().graphicsLayer {
                        scaleX=scale;scaleY=scale;translationX=x;translationY=y
                    },previewEnabled=false,fullSize=true)
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                if(paths.size>1)TextButton(onClick={index--},enabled=index>0){Text("上一张",color=if(index>0)Color.White else Color.Gray)}
                TextButton(onClick={scale=1f;x=0f;y=0f}){Text("还原",color=Color.White)}
                if(paths.size>1)TextButton(onClick={index++},enabled=index<paths.lastIndex){Text("下一张",color=if(index<paths.lastIndex)Color.White else Color.Gray)}
            }
        }
    }
}
