package dev.lzk.fitlog

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.features.FriendsViewModel
import dev.lzk.fitlog.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SecondsAndRefreshFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="seconds-ui.db"
    private lateinit var container:AppContainer
    private val models=ViewModelStore()
    @Before fun setup()=runBlocking {
        context.deleteDatabase(name);container=AppContainer(context,name);container.ready.await()
        container.preferences.save(UserPreferences(onboardingCompleted=true,restEnabled=false))
    }
    @After fun close(){compose.activityRule.scenario.close();models.clear();container.close();context.deleteDatabase(name)}
    private fun waitText(text:String)=compose.waitUntil(15_000){compose.onAllNodesWithText(text,substring=true).fetchSemanticsNodes().isNotEmpty()}
    @Test fun plankInputAndHistoryUseSecondsAndHideWeight() {
        val session=runBlocking {container.workouts.startWorkout(exerciseIds=listOf("plank")).session.id}
        compose.setContent {FitLogTheme {FitLogApp(container)}}
        waitText("继续训练");compose.onNodeWithText("继续训练").performScrollTo().performClick()
        waitText("完成本组")
        assertTrue(compose.onAllNodesWithText("保持时长（秒）").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithContentDescription("输入第1组秒数").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("45")
        compose.onNodeWithText("保存",useUnmergedTree=true).performClick()
        compose.onAllNodesWithText("重量（kg）").assertCountEquals(0)
        compose.onNodeWithText("完成本组").performClick()
        compose.onNodeWithText("结束").performClick()
        waitText("结束训练")
        compose.onNodeWithText("结束训练").performClick()
        waitText("45 秒")
        val saved=runBlocking {container.workouts.observeWorkout(session).first()!!}
        assertEquals(45,saved.exercises.single().sets.first().reps)
        assertEquals(RecordMetric.SECONDS,saved.exercises.single().recordMetricSnapshot)
    }
    @Test fun templateOffersSecondsTargetsWithoutWeight() {
        compose.setContent {FitLogTheme {FitLogApp(container)}}
        waitText("历史")
        compose.onNodeWithText("动作",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("open-templates").performClick()
        waitText("新建");compose.onNodeWithText("新建").performClick()
        waitText("模板名称");compose.onNodeWithText("模板名称").performTextInput("支撑日")
        compose.onNodeWithText("添加模板动作").performScrollTo().performClick()
        compose.onNodeWithTag("template-exercise-search").performTextInput("平板")
        compose.onNodeWithText("平板支撑").performClick()
        waitText("调整");compose.onNodeWithText("调整").performClick()
        compose.onNodeWithText("最少秒数").assertExists()
        compose.onNodeWithText("最多秒数").assertExists()
        compose.onNodeWithText("默认重量（kg）").assertDoesNotExist()
    }
    @Test fun enteringFriendsRefreshesSilentlyAndReentryFetchesAgain() {
        val fake=FakeSocial()
        val reads=AtomicInteger()
        var nickname="第一次"
        val repository=object:FriendsRepository by fake {
            override suspend fun friends():List<Friend> {
                reads.incrementAndGet();delay(150)
                return listOf(Friend(fake.peer.copy(nickname=nickname),"",true,false))
            }
        }
        val model=FriendsViewModel(repository);models.put("friends",model)
        var visible by mutableStateOf(true)
        compose.setContent {FitLogTheme {Column {
            TextButton(onClick={visible=!visible}) {Text("切换页面")}
            Box(Modifier.weight(1f)) {if(visible) FriendsScreen(model,fake) else Text("另一个页面")}
        }}}
        waitText("第一次")
        compose.onNodeWithText("刷新").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        compose.runOnIdle {assertFalse(model.busy.value)}
        val before=reads.get();nickname="再次进入"
        compose.onNodeWithText("切换页面").performClick()
        compose.onNodeWithText("切换页面").performClick()
        waitText("再次进入")
        assertTrue(reads.get()>before)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
    }
}
