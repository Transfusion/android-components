/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.tabs

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.TestCoroutineScope
import mozilla.components.browser.session.storage.SessionStorage
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.SearchState
import mozilla.components.browser.state.state.SessionState.Source
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.state.recover.RecoverableTab
import mozilla.components.browser.state.state.recover.toRecoverableTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineSession.LoadUrlFlags
import mozilla.components.concept.engine.EngineSessionState
import mozilla.components.support.test.any
import mozilla.components.support.test.argumentCaptor
import mozilla.components.support.test.ext.joinBlocking
import mozilla.components.support.test.libstate.ext.waitUntilIdle
import mozilla.components.support.test.mock
import mozilla.components.support.test.rule.MainCoroutineRule
import mozilla.components.support.test.whenever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

const val DAY_IN_MS = 24 * 60 * 60 * 1000L

class TabsUseCasesTest {
    private val dispatcher: TestCoroutineDispatcher = TestCoroutineDispatcher()

    private lateinit var store: BrowserStore
    private lateinit var tabsUseCases: TabsUseCases
    private lateinit var engine: Engine
    private lateinit var engineSession: EngineSession

    @get:Rule
    val coroutinesTestRule = MainCoroutineRule(dispatcher)

    @Before
    fun setup() {
        engineSession = mock()
        engine = mock()

        whenever(engine.createSession(anyBoolean(), any())).thenReturn(engineSession)
        store = BrowserStore(
            middleware = EngineMiddleware.create(
                engine = engine
            )
        )
        tabsUseCases = TabsUseCases(store)
    }

    @Test
    fun `SelectTabUseCase - tab is marked as selected in store`() {
        val tab = createTab("https://mozilla.org")
        val otherTab = createTab("https://firefox.com")
        store.dispatch(TabListAction.AddTabAction(otherTab)).joinBlocking()
        store.dispatch(TabListAction.AddTabAction(tab)).joinBlocking()

        assertEquals(otherTab.id, store.state.selectedTabId)
        assertEquals(otherTab, store.state.selectedTab)

        tabsUseCases.selectTab(tab.id)
        store.waitUntilIdle()
        assertEquals(tab.id, store.state.selectedTabId)
        assertEquals(tab, store.state.selectedTab)
    }

    @Test
    fun `RemoveTabUseCase - session will be removed from store`() {
        val tab = createTab("https://mozilla.org")
        store.dispatch(TabListAction.AddTabAction(tab)).joinBlocking()
        assertEquals(1, store.state.tabs.size)

        tabsUseCases.removeTab(tab.id)
        store.waitUntilIdle()
        assertEquals(0, store.state.tabs.size)
    }

    @Test
    fun `RemoveTabUseCase - remove by ID and select parent if it exists`() {
        val parentTab = createTab("https://firefox.com")
        store.dispatch(TabListAction.AddTabAction(parentTab)).joinBlocking()

        val tab = createTab("https://mozilla.org", parent = parentTab)
        store.dispatch(TabListAction.AddTabAction(tab, select = true)).joinBlocking()
        assertEquals(2, store.state.tabs.size)
        assertEquals(tab.id, store.state.selectedTabId)

        tabsUseCases.removeTab(tab.id, selectParentIfExists = true)
        store.waitUntilIdle()
        assertEquals(1, store.state.tabs.size)
        assertEquals(parentTab.id, store.state.selectedTabId)
    }

    @Test
    fun `RemoveTabsUseCase - list of sessions can be removed`() {
        val tab = createTab("https://mozilla.org")
        val otherTab = createTab("https://firefox.com")
        store.dispatch(TabListAction.AddTabAction(otherTab)).joinBlocking()
        store.dispatch(TabListAction.AddTabAction(tab)).joinBlocking()

        assertEquals(otherTab.id, store.state.selectedTabId)
        assertEquals(otherTab, store.state.selectedTab)

        tabsUseCases.removeTabs(listOf(tab.id, otherTab.id))
        store.waitUntilIdle()
        assertEquals(0, store.state.tabs.size)
    }

    @Test
    fun `AddNewTabUseCase - session will be added to store`() {
        tabsUseCases.addTab("https://www.mozilla.org")

        store.waitUntilIdle()
        assertEquals(1, store.state.tabs.size)
        assertEquals("https://www.mozilla.org", store.state.tabs[0].content.url)
        assertFalse(store.state.tabs[0].content.private)
    }

    @Test
    fun `AddNewTabUseCase - private session will be added to store`() {
        tabsUseCases.addTab("https://www.mozilla.org", private = true)

        store.waitUntilIdle()
        assertEquals(1, store.state.tabs.size)
        assertEquals("https://www.mozilla.org", store.state.tabs[0].content.url)
        assertTrue(store.state.tabs[0].content.private)
    }

    @Test
    fun `AddNewTabUseCase will not load URL if flag is set to false`() {
        tabsUseCases.addTab("https://www.mozilla.org", startLoading = false)

        store.waitUntilIdle()
        assertEquals(1, store.state.tabs.size)
        assertEquals("https://www.mozilla.org", store.state.tabs[0].content.url)
        verify(engineSession, never()).loadUrl(anyString(), any(), any(), any())
    }

    @Test
    fun `AddNewTabUseCase will load URL if flag is set to true`() {
        tabsUseCases.addTab("https://www.mozilla.org", startLoading = true)

        store.waitUntilIdle()
        dispatcher.advanceUntilIdle()
        assertEquals(1, store.state.tabs.size)
        assertEquals("https://www.mozilla.org", store.state.tabs[0].content.url)
        verify(engineSession, times(1)).loadUrl("https://www.mozilla.org")
    }

    @Test
    fun `AddNewTabUseCase forwards load flags to engine`() {
        tabsUseCases.addTab.invoke("https://www.mozilla.org", flags = LoadUrlFlags.external(), startLoading = true)

        store.waitUntilIdle()
        dispatcher.advanceUntilIdle()
        assertEquals(1, store.state.tabs.size)
        assertEquals("https://www.mozilla.org", store.state.tabs[0].content.url)
        verify(engineSession, times(1)).loadUrl("https://www.mozilla.org", null, LoadUrlFlags.external(), null)
    }

    /*
    @Test
    fun `AddNewTabUseCase uses provided engine session`() {
        val store = BrowserStore()
        val sessionManager = spy(SessionManager(mock(), store))
        val engineSession: EngineSession = mock()

        val useCases = TabsUseCases(store, sessionManager)
        useCases.addTab.invoke("https://www.mozilla.org", engineSession = engineSession, startLoading = true)
        assertEquals(1, store.state.tabs.size)
        assertEquals(engineSession, store.state.tabs.first().engineState.engineSession)
    }

    @Test
    fun `AddNewTabUseCase uses provided contextId`() {
        val sessionManager = spy(SessionManager(mock()))
        val useCases = TabsUseCases(BrowserStore(), sessionManager)

        assertEquals(0, sessionManager.size)

        useCases.addTab("https://www.mozilla.org", contextId = "1")

        assertEquals(1, sessionManager.size)
        assertEquals("https://www.mozilla.org", sessionManager.selectedSessionOrThrow.url)
        assertEquals("1", sessionManager.selectedSessionOrThrow.contextId)
        assertEquals(Source.NEW_TAB, sessionManager.selectedSessionOrThrow.source)
        assertFalse(sessionManager.selectedSessionOrThrow.private)
    }

    @Test
    fun `AddNewPrivateTabUseCase will not load URL if flag is set to false`() {
        val sessionManager = spy(SessionManager(mock()))
        val store: BrowserStore = mock()
        val useCases = TabsUseCases(store, sessionManager)

        useCases.addPrivateTab("https://www.mozilla.org", startLoading = false)

        val actionCaptor = argumentCaptor<EngineAction.LoadUrlAction>()
        verify(store, never()).dispatch(actionCaptor.capture())
    }

    @Test
    fun `AddNewPrivateTabUseCase will load URL if flag is set to true`() {
        val sessionManager = spy(SessionManager(mock()))
        val store: BrowserStore = mock()
        val useCases = TabsUseCases(store, sessionManager)

        useCases.addPrivateTab("https://www.mozilla.org", startLoading = true)

        val actionCaptor = argumentCaptor<EngineAction.LoadUrlAction>()
        verify(store).dispatch(actionCaptor.capture())
        assertEquals("https://www.mozilla.org", actionCaptor.value.url)
    }

    @Test
    fun `AddNewPrivateTabUseCase forwards load flags to engine`() {
        val sessionManager: SessionManager = mock()
        val store: BrowserStore = mock()
        val useCases = TabsUseCases(store, sessionManager)

        useCases.addPrivateTab.invoke("https://www.mozilla.org", LoadUrlFlags.select(LoadUrlFlags.EXTERNAL))
        val actionCaptor = argumentCaptor<EngineAction.LoadUrlAction>()
        verify(store).dispatch(actionCaptor.capture())
        assertEquals("https://www.mozilla.org", actionCaptor.value.url)
        assertEquals(LoadUrlFlags.select(LoadUrlFlags.EXTERNAL), actionCaptor.value.flags)
    }

    @Test
    fun `AddNewPrivateTabUseCase uses provided engine session`() {
        val store = BrowserStore()
        val sessionManager = spy(SessionManager(mock(), store))
        val engineSession: EngineSession = mock()

        val useCases = TabsUseCases(store, sessionManager)
        useCases.addPrivateTab.invoke("https://www.mozilla.org", engineSession = engineSession, startLoading = true)
        assertEquals(1, store.state.tabs.size)
        assertEquals(engineSession, store.state.tabs.first().engineState.engineSession)
    }

    @Test
    fun `RemoveAllTabsUseCase will remove all sessions`() {
        val sessionManager = spy(SessionManager(mock()))
        val useCases = TabsUseCases(BrowserStore(), sessionManager)

        useCases.addPrivateTab("https://www.mozilla.org")
        useCases.addTab("https://www.mozilla.org")
        assertEquals(2, sessionManager.size)

        useCases.removeAllTabs()
        assertEquals(0, sessionManager.size)
        verify(sessionManager).removeSessions()
    }

    @Test
    fun `RemoveNormalTabsUseCase and RemovePrivateTabsUseCase will remove sessions for particular type of tabs private or normal`() {
        val sessionManager = spy(SessionManager(mock()))
        val useCases = TabsUseCases(BrowserStore(), sessionManager)

        val session1 = Session("https://www.mozilla.org")
        session1.customTabConfig = mock()
        sessionManager.add(session1)
        useCases.addPrivateTab("https://www.mozilla.org")
        useCases.addTab("https://www.mozilla.org")
        assertEquals(3, sessionManager.size)

        useCases.removeNormalTabs.invoke()
        assertEquals(2, sessionManager.all.size)

        useCases.addPrivateTab("https://www.mozilla.org")
        useCases.addTab("https://www.mozilla.org")
        useCases.addTab("https://www.mozilla.org")
        assertEquals(5, sessionManager.size)

        useCases.removePrivateTabs.invoke()
        assertEquals(3, sessionManager.size)

        useCases.removeNormalTabs.invoke()
        assertEquals(1, sessionManager.size)

        assertTrue(sessionManager.all[0].isCustomTabSession())
    }

    @Test
    fun `RestoreUseCase - filters based on tab timeout`() = runBlocking {
        val sessionManager: SessionManager = mock()
        val useCases = TabsUseCases(BrowserStore(), sessionManager)

        val now = System.currentTimeMillis()
        val tabs = listOf(
            createTab("https://mozilla.org", lastAccess = now).toRecoverableTab(),
            createTab("https://firefox.com", lastAccess = now - 2 * DAY_IN_MS).toRecoverableTab(),
            createTab("https://getpocket.com", lastAccess = now - 3 * DAY_IN_MS).toRecoverableTab()
        )

        val sessionStorage: SessionStorage = mock()
        useCases.restore(sessionStorage, tabTimeoutInMs = DAY_IN_MS)

        val predicateCaptor = argumentCaptor<(RecoverableTab) -> Boolean>()
        verify(sessionStorage).restore(predicateCaptor.capture())

        // Only the first tab should be restored, all others "timed out."
        val restoredTabs = tabs.filter(predicateCaptor.value)
        assertEquals(1, restoredTabs.size)
        assertEquals(tabs.first(), restoredTabs.first())
    }

    @Test
    fun `Restore single tab, update selection - SessionManager and BrowserStore are in sync`() {
        val store = BrowserStore()
        val sessionManager = SessionManager(store = store, engine = mock())

        sessionManager.add(Session("https://www.mozilla.org", id = "mozilla"))

        assertEquals(1, sessionManager.sessions.size)
        assertEquals(1, store.state.tabs.size)
        assertEquals("mozilla", sessionManager.selectedSessionOrThrow.id)
        assertEquals("mozilla", store.state.selectedTabId)

        val closedTab = RecoverableTab(
            id = "wikipedia",
            url = "https://www.wikipedia.org"
        )

        val useCases = TabsUseCases(store, sessionManager)
        useCases.restore(closedTab)

        assertEquals(2, sessionManager.sessions.size)
        assertEquals(2, store.state.tabs.size)
        assertEquals("wikipedia", sessionManager.selectedSessionOrThrow.id)
        assertEquals("wikipedia", store.state.selectedTabId)
    }

    @Test
    fun `selectOrAddTab selects already existing tab`() {
        val store = BrowserStore()
        val sessionManager = SessionManager(engine = mock(), store = store)
        val useCases = TabsUseCases(store, sessionManager)

        sessionManager.add(Session("https://www.mozilla.org", id = "mozilla"))
        sessionManager.add(Session("https://firefox.com", id = "firefox"))
        sessionManager.add(Session("https://getpocket.com", id = "pocket"))

        assertEquals("mozilla", store.state.selectedTabId)
        assertEquals(3, store.state.tabs.size)

        useCases.selectOrAddTab("https://getpocket.com")

        assertEquals("pocket", store.state.selectedTabId)
        assertEquals(3, store.state.tabs.size)
    }

    @Test
    fun `selectOrAddTab adds new tab if no matching existing tab could be found`() {
        val store = BrowserStore()
        val sessionManager = SessionManager(engine = mock(), store = store)
        val useCases = TabsUseCases(store, sessionManager)

        sessionManager.add(Session("https://www.mozilla.org", id = "mozilla"))
        sessionManager.add(Session("https://firefox.com", id = "firefox"))
        sessionManager.add(Session("https://getpocket.com", id = "pocket"))

        assertEquals("mozilla", store.state.selectedTabId)
        assertEquals(3, store.state.tabs.size)

        useCases.selectOrAddTab("https://youtube.com")

        assertNotEquals("mozilla", store.state.selectedTabId)
        assertEquals(4, store.state.tabs.size)
        assertEquals("https://youtube.com", store.state.tabs.last().content.url)
        assertEquals("https://youtube.com", store.state.selectedTab!!.content.url)
    }

    @Test
    fun `duplicateTab creates a duplicate of the given tab`() {
        val store = BrowserStore()
        val sessionManager = SessionManager(engine = mock(), store = store)

        val useCases = TabsUseCases(store, sessionManager)

        sessionManager.add(Session("https://www.mozilla.org", id = "mozilla"))

        val engineSessionState: EngineSessionState = mock()
        store.dispatch(
            EngineAction.UpdateEngineSessionStateAction("mozilla", engineSessionState)
        ).joinBlocking()

        val tab = store.state.findTab("mozilla")!!
        useCases.duplicateTab.invoke(tab)

        assertEquals(2, store.state.tabs.size)

        assertEquals("mozilla", store.state.tabs[0].id)
        assertNotEquals("mozilla", store.state.tabs[1].id)
        assertFalse(store.state.tabs[0].content.private)
        assertFalse(store.state.tabs[1].content.private)
        assertEquals("https://www.mozilla.org", store.state.tabs[0].content.url)
        assertEquals("https://www.mozilla.org", store.state.tabs[1].content.url)
        assertEquals(engineSessionState, store.state.tabs[0].engineState.engineSessionState)
        assertEquals(engineSessionState, store.state.tabs[1].engineState.engineSessionState)
        assertNull(store.state.tabs[0].parentId)
        assertEquals("mozilla", store.state.tabs[1].parentId)
    }

    @Test
    fun `duplicateTab creates duplicates of private tabs`() {
        val store = BrowserStore()
        val sessionManager = SessionManager(engine = mock(), store = store)

        val useCases = TabsUseCases(store, sessionManager)

        sessionManager.add(Session("https://www.mozilla.org", id = "mozilla", private = true))

        val tab = store.state.findTab("mozilla")!!
        useCases.duplicateTab.invoke(tab)

        assertEquals(2, store.state.tabs.size)
        assertTrue(store.state.tabs[0].content.private)
        assertTrue(store.state.tabs[1].content.private)
    }

    @Test
    fun `duplicateTab keeps contextId`() {
        val store = BrowserStore()
        val sessionManager = SessionManager(engine = mock(), store = store)

        val useCases = TabsUseCases(store, sessionManager)

        sessionManager.add(Session("https://www.mozilla.org", id = "mozilla", contextId = "work"))

        val tab = store.state.findTab("mozilla")!!
        useCases.duplicateTab.invoke(tab)

        assertEquals(2, store.state.tabs.size)
        assertEquals("work", store.state.tabs[0].contextId)
        assertEquals("work", store.state.tabs[1].contextId)
    }*/
}
