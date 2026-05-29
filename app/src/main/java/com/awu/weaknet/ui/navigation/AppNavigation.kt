/**
 * @author awu
 * @date 2026-05-26
 * @desc 应用导航图：基于 Navigation Compose 管理主页面、配置列表、配置编辑和应用选择器之间的路由
 */
package com.awu.weaknet.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.awu.weaknet.PermissionUiState
import com.awu.weaknet.data.repository.ProfileRepository
import com.awu.weaknet.ui.screen.*
import kotlinx.coroutines.launch

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Profiles : Screen("profiles")
    data object ProfileEdit : Screen("profile_edit/{profileId}") {
        fun createRoute(profileId: String? = null) =
            "profile_edit/${profileId ?: "new"}"
    }
    data object AppSelector : Screen("app_selector")
}

// 使用 application context 创建单例，避免 Activity 重建导致 DataStore 多实例崩溃
@Composable
fun rememberProfileRepository(): ProfileRepository {
    val context = LocalContext.current
    return remember { ProfileRepository(context.applicationContext) }
}

@Composable
fun AppNavigation(
    onRequestVpn: () -> Unit,
    permissionState: State<PermissionUiState> = androidx.compose.runtime.mutableStateOf(PermissionUiState.Granted),
    onRequestOverlay: () -> Unit = {},
) {
    val navController = rememberNavController()
    val repository = rememberProfileRepository()
    val scope = rememberCoroutineScope()

    NavHost(navController = navController, startDestination = Screen.Home.route) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateProfiles = { navController.navigate(Screen.Profiles.route) },
                onNavigateAppSelector = { navController.navigate(Screen.AppSelector.route) },
                onNavigateProfileEdit = { id ->
                    navController.navigate(Screen.ProfileEdit.createRoute(id))
                },
                onRequestVpn = onRequestVpn,
                permissionState = permissionState,
                onRequestOverlay = onRequestOverlay,
            )
        }

        composable(Screen.Profiles.route) {
            ProfileListScreen(
                repository = repository,
                onNavigateBack = { navController.popBackStack() },
                onNavigateEdit = { id ->
                    navController.navigate(Screen.ProfileEdit.createRoute(id))
                },
            )
        }

        composable(
            route = Screen.ProfileEdit.route,
            arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
        ) { entry ->
            val profileId = entry.arguments?.getString("profileId")
            val isNew = profileId == "new"
            val customProfiles by repository.customProfiles.collectAsState(initial = emptyList())
            val existingProfile = if (!isNew && profileId != null) {
                customProfiles.find { it.id == profileId }
            } else null

            // profile 不存在且 DataStore 已加载 → 自动返回
            if (!isNew && profileId != null && existingProfile == null && customProfiles.isNotEmpty()) {
                LaunchedEffect(Unit) { navController.popBackStack() }
                return@composable
            }

            if (isNew || existingProfile != null) {
                ProfileEditScreen(
                    profileId = if (isNew) null else profileId,
                    existingProfile = existingProfile,
                    onSave = { profile ->
                        if (isNew) {
                            repository.saveProfile(profile)
                        } else {
                            repository.updateProfile(profile)
                        }
                    },
                    onNavigateBack = { navController.popBackStack() },
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            }
        }

        composable(Screen.AppSelector.route) {
            AppSelectorScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
