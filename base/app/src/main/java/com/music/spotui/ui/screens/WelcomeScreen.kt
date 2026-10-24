package com.music.spotui.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.ui.components.pressable
import com.music.spotui.ui.theme.AppSans
import com.music.spotui.ui.theme.ScGrey
import com.music.spotui.ui.theme.ScPrimary
import com.music.spotui.ui.theme.ScSecondary
import com.music.spotui.ui.theme.ScTint

/**
 * 1:1 Jetpack Compose port of redretep/spotify-clone components/Login/Login.tsx & styles.ts.
 */
@Composable
fun WelcomeScreen(onLogin: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScPrimary),
    ) {
        Image(
            painter = painterResource(R.drawable.sc_login_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 35.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.sc_logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(53.dp)
                    .padding(bottom = 0.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Millions of Songs.\nFree on Spotify.",
                color = Color.White,
                fontFamily = AppSans,
                fontWeight = FontWeight.Bold,
                fontSize = 35.sp,
                lineHeight = 40.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(25.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable(0.96f, onClick = onLogin)
                    .clip(CircleShape)
                    .background(ScTint)
                    .padding(vertical = 18.dp),
            ) {
                Text(
                    text = "Sign in",
                    color = ScSecondary,
                    fontFamily = AppSans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "NOTE: You won't be sharing your credentials",
                color = ScGrey,
                fontFamily = AppSans,
                fontStyle = FontStyle.Italic,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(150.dp))
        }
    }
}
