package com.vlesscardvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.core.AutoCheckProfile
import com.vlesscardvpn.core.SiteCheckCatalog
import com.vlesscardvpn.core.Store

@Composable
fun WhatToCheckScreen() {
    val settings by Store.settings.collectAsState()
    val selected=AutoCheckProfile.of(settings.autoChecks).selected
    var search by rememberSaveable { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var domain by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    data class Item(val id:String,val title:String,val detail:String,val custom:Boolean=false)
    val native=listOf(
        Item("telegram","Telegram","Ответ дата-центра"), Item("stability","Telegram · устойчивость","Серия ответов"),
        Item("voice","Звонки","Доступность UDP, не звонок"), Item("discord","Discord","Публичный gateway, не аккаунт"),
        Item("youtube","YouTube","Веб-доступ, не видео"), Item("instagram","Instagram","Веб-доступ, не лента")
    )
    val presets=SiteCheckCatalog.presets.map { Item(SiteCheckCatalog.id(it.domain),it.title,it.domain+" · HTTPS") }
    val presetDomains=SiteCheckCatalog.presets.map {it.domain}.toSet()
    val customs=settings.customCheckDomains.filter {it !in presetDomains}.map { Item(SiteCheckCatalog.id(it),it,it+" · HTTPS",true) }
    val all=native+presets+customs
    val visible=all.filter { search.isBlank() || it.title.contains(search,ignoreCase=true) || it.detail.contains(search,ignoreCase=true) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(6.dp),contentPadding=PaddingValues(vertical=16.dp)) {
        item { Text("Что проверять",style=MaterialTheme.typography.headlineMedium) }
        item { Text("Выбранные проверки обязательны при поиске серверов и резервов в Авто. HTTPS обязателен всегда. Изменения действуют со следующей проверки.",style=MaterialTheme.typography.bodyMedium) }
        item { Text("Сайты: только HTTPS-ответ, не вход, видео или работа приложения. Перенаправление, отказ и тайм-аут — неизвестно, не успех. Большое число выбранных сайтов может увеличить время поиска.",style=MaterialTheme.typography.bodySmall) }
        item {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value=search,onValueChange={search=it},label={Text("Поиск сервисов и доменов")},singleLine=true,modifier=Modifier.weight(1f))
                Button(onClick={domain="";error="";adding=true},modifier=Modifier.semantics {contentDescription="Добавить домен"}) {Text("+")}
            }
        }
        item { Text("Выбрано: ${selected.size}",style=MaterialTheme.typography.labelLarge) }
        if(visible.isEmpty()) item {Text("Ничего не найдено")}
        items(visible,key={it.id}) { item ->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {Text(item.title);Text(item.detail,style=MaterialTheme.typography.bodySmall)}
                if(item.custom) TextButton(onClick={Store.update { state -> state.copy(settings=state.settings.copy(
                    customCheckDomains=state.settings.customCheckDomains.filterNot {SiteCheckCatalog.id(it)==item.id},autoChecks=state.settings.autoChecks-item.id)) }}) {Text("Удалить")}
                Switch(checked=item.id in selected,modifier=Modifier.semantics {contentDescription="Проверять ${item.title}"},onCheckedChange={on->
                    Store.update {state->state.copy(settings=state.settings.copy(autoChecks=if(on) state.settings.autoChecks+item.id else state.settings.autoChecks-item.id))}
                })
            }
            HorizontalDivider()
        }
    }
    if(adding) AlertDialog(onDismissRequest={adding=false},title={Text("Добавить домен")},text={
        Column {
            Text("Введите только домен, например example.com. Проверка выполняется через сервер по HTTPS.")
            OutlinedTextField(domain,{domain=it;error=""},singleLine=true,label={Text("Домен")},isError=error.isNotEmpty())
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={TextButton(onClick={
        val host=SiteCheckCatalog.domain(domain)
        when {
            host==null -> error="Введите корректный публичный домен без https://, порта и пути"
            settings.customCheckDomains.size>=100 && host !in settings.customCheckDomains && host !in presetDomains -> error="Сохранено 100 своих доменов"
            else -> { Store.update {state -> state.copy(settings=state.settings.copy(
                customCheckDomains=if(host in presetDomains) state.settings.customCheckDomains else (state.settings.customCheckDomains+host).distinct(),
                autoChecks=state.settings.autoChecks+SiteCheckCatalog.id(host))) }; adding=false }
        }
    }) {Text("Добавить и проверять")}},dismissButton={TextButton(onClick={adding=false}) {Text("Отмена")}})
}
