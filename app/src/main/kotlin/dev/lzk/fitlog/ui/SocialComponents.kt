package dev.lzk.fitlog.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun Instant.socialTime():String =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(this)

@Composable internal fun SocialImage(repository:SocialRepository,path:String,modifier:Modifier=Modifier) {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var bitmap by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(repository,path,lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                bitmap=withContext(Dispatchers.IO) {
                    val bytes=repository.image(path)
                    // Decode at thumbnail size; source is bounded to 2 MB and 1600 px by upload.
                    val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=64_000_000) {"图片尺寸过大"}
                    var sample=1
                    while(maxOf(bounds.outWidth,bounds.outHeight)/sample>800) sample*=2
                    val options=BitmapFactory.Options().apply {inSampleSize=sample}
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                }
                awaitCancellation()
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) { failed=true }
            finally {bitmap=null}
        }
    }
    Box(modifier,contentAlignment=Alignment.Center) {
        bitmap?.let {Image(it.asImageBitmap(),contentDescription="图片",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop)}
            ?: Text(if(failed) "图片暂不可用" else "…",style=MaterialTheme.typography.labelSmall)
    }
}

@Composable internal fun SocialAvatar(repository:SocialRepository?,profile:SocialProfile,onClick:()->Unit) {
    Surface(onClick=onClick,shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer,modifier=Modifier.size(48.dp).semanticsLabel(profile.nickname+"的头像")) {
        if(repository!=null && profile.avatarPath!=null) SocialImage(repository,requireNotNull(profile.avatarPath),Modifier.fillMaxSize().clip(CircleShape))
        else Box(contentAlignment=Alignment.Center) {Text(profile.nickname.take(1),style=MaterialTheme.typography.titleLarge)}
    }
}

@Composable internal fun SocialHeader(title:String,onBack:()->Unit,actions:@Composable RowScope.()->Unit={}) {
    Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick=onBack) {LineIcon(Symbol.BACK,Modifier.size(24.dp).semanticsLabel("返回"))}
        Text(title,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleLarge)
        actions()
    }
}

@Composable internal fun rememberImagePicker(onImage:suspend (ByteArray)->Unit,onError:(String)->Unit):()->Unit {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val currentImage by rememberUpdatedState(onImage)
    val currentError by rememberUpdatedState(onError)
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {uri:Uri? ->
        if(uri!=null) scope.launch {
            try {
                val jpeg=withContext(Dispatchers.IO) {
                    val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    context.contentResolver.openInputStream(uri).use {BitmapFactory.decodeStream(it,null,bounds)}
                    require(bounds.outWidth>0 && bounds.outHeight>0) {"无法读取这张图片"}
                    var sample=1
                    while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1600) sample*=2
                    val options=BitmapFactory.Options().apply {inSampleSize=sample}
                    val bitmap=context.contentResolver.openInputStream(uri).use {BitmapFactory.decodeStream(it,null,options)}
                        ?: error("无法读取这张图片")
                    try {
                        val output=ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,82,output)
                        require(output.size()<=2_000_000) {"图片较大，请选择尺寸更小的图片"}
                        output.toByteArray()
                    } finally {bitmap.recycle()}
                }
                currentImage(jpeg)
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {currentError(e.message ?: "图片处理失败")}
        }
    }
    return {picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}
}
