package dev.lzk.fitlog.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun Instant.socialTime():String =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(this)

internal object AvatarImages {
    private val images=object:android.util.LruCache<String,android.graphics.Bitmap>(2*1024*1024) {
        override fun sizeOf(key:String,value:android.graphics.Bitmap)=value.allocationByteCount
    }
    fun get(key:String)=images.get(key)
    fun put(key:String,bitmap:android.graphics.Bitmap){images.put(key,bitmap)}
    fun clear(){images.evictAll();KeptImages.clear()}
}

/** Larger images that must survive a page's exit animation (profile banners); bounded and released with the avatars. */
internal object KeptImages {
    private val images=object:android.util.LruCache<String,android.graphics.Bitmap>(8*1024*1024) {
        override fun sizeOf(key:String,value:android.graphics.Bitmap)=value.allocationByteCount
    }
    fun get(key:String)=images.get(key)
    fun put(key:String,bitmap:android.graphics.Bitmap){images.put(key,bitmap)}
    fun clear(){images.evictAll()}
}

@Composable internal fun SocialImage(repository:SocialRepository,path:String,modifier:Modifier=Modifier,avatar:Boolean=false,previewEnabled:Boolean=true,gallery:List<String> = listOf(path),fullSize:Boolean=false,onLongClick:(()->Unit)?=null,
    contentScale:ContentScale?=null,keep:Boolean=false,onAspect:((Float)->Unit)?=null,fallback:@Composable ()->Unit={Text("…",style=MaterialTheme.typography.labelSmall)}) {
    // Avatars and kept images (banners) are cached so a page's exit animation never falls back to a placeholder.
    fun cached(key:String)=if(avatar) AvatarImages.get(key) else if(keep) KeptImages.get(key) else null
    var preview by remember(path){mutableStateOf(false)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val profileFlow=remember(repository) {
        (repository as? FriendsRepository)?.profile ?: kotlinx.coroutines.flow.MutableStateFlow<SocialProfile?>(null)
    }
    val currentProfile by profileFlow.collectAsStateWithLifecycle()
    val account=currentProfile?.id.orEmpty()
    val cacheKey=System.identityHashCode(repository).toString()+":"+account+":"+path
    var bitmap by remember(cacheKey) { mutableStateOf(cached(cacheKey)) }
    var failed by remember(cacheKey) { mutableStateOf(false) }
    // Width / height once known, so a chat bubble can take the picture's shape.
    val aspect by rememberUpdatedState(onAspect)
    LaunchedEffect(repository,path,lifecycle,cacheKey) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                bitmap=bitmap ?: cached(cacheKey) ?: withContext(Dispatchers.IO) {
                    val bytes=repository.image(path)
                    // Decode at thumbnail size; source is bounded to 2 MB and 1600 px by upload.
                    val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=64_000_000) {"图片尺寸过大"}
                    var sample=1
                    while(maxOf(bounds.outWidth,bounds.outHeight)/sample>(if(avatar)128 else if(fullSize)1600 else 800)) sample*=2
                    val options=BitmapFactory.Options().apply {inSampleSize=sample}
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                }
                bitmap?.let {if(avatar) AvatarImages.put(cacheKey,it) else if(keep) KeptImages.put(cacheKey,it)}
                bitmap?.let {if(it.height>0) aspect?.invoke(it.width.toFloat()/it.height)}
                awaitCancellation()
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) { failed=true }
            finally {bitmap=null}
        }
    }
    Box(modifier.then(if(!avatar && previewEnabled)Modifier.combinedClickable(onClickLabel="查看大图",onClick={preview=true},onLongClick=onLongClick) else Modifier),contentAlignment=Alignment.Center) {
        // A stopped navigation destination can remain visible during its exit animation.
        // Its loader has released bitmap, so draw the account-scoped thumbnail directly.
        val displayed=bitmap ?: cached(cacheKey)
        if(displayed!=null) Image(displayed.asImageBitmap(),contentDescription="图片",modifier=Modifier.fillMaxSize(),contentScale=contentScale ?: if(avatar)ContentScale.Crop else ContentScale.Fit)
        else if(avatar) {if(failed) fallback()}
        else if(!failed) fallback() else Text("图片暂不可用",style=MaterialTheme.typography.labelSmall)
    }
    if(preview) SocialPhotoViewer(repository,gallery.ifEmpty{listOf(path)},path){preview=false}
}

@Composable internal fun SocialAvatar(repository:SocialRepository?,profile:SocialProfile,onClick:()->Unit) =
    SocialAvatar(repository,profile,48.dp,Modifier,onClick)

@Composable internal fun SocialAvatar(repository:SocialRepository?,profile:SocialProfile,size:androidx.compose.ui.unit.Dp=48.dp,modifier:Modifier=Modifier,onClick:()->Unit) {
    val initial=if(size>=72.dp) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge
    Surface(onClick=onClick,shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer,modifier=modifier.size(size).semanticsLabel(profile.nickname+"的头像")) {
        if(repository!=null && profile.avatarPath!=null) SocialImage(repository,requireNotNull(profile.avatarPath),Modifier.fillMaxSize().clip(CircleShape),avatar=true) {Text(profile.nickname.take(1),style=initial)}
        else Box(contentAlignment=Alignment.Center) {Text(profile.nickname.take(1),style=initial)}
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

@Composable internal fun FitLogIdentityRow(id:String) {
    val clipboard=androidx.compose.ui.platform.LocalClipboard.current
    val scope=rememberCoroutineScope()
    var copied by remember(id){mutableStateOf(false)}
    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
        androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f)) {
            Text("FitLog ID："+id,style=MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick={scope.launch {
            clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("FitLog ID",id)))
            copied=true;delay(1500);copied=false
        }},modifier=Modifier.semanticsLabel("复制 FitLog ID")) {
            Text(if(copied)"已复制" else "复制",maxLines=1)
        }
    }
}
