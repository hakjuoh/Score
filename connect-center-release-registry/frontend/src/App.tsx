import { useEffect, useState } from 'react'
import { Route, Routes } from 'react-router-dom'
import { apiBaseUrl, fetchJson } from '@/lib/registry'
import { CatalogPage } from '@/pages/catalog-page'
import { LandingPage } from '@/pages/landing-page'
import { LibraryDetailPage } from '@/pages/library-detail-page'
import { LoginPage } from '@/pages/login-page'
import { ProfilePage } from '@/pages/profile-page'
import type { AppData, AuthUser, LibraryCatalogEntry } from '@/types/registry'

function App() {
  const [libraries, setLibraries] = useState<LibraryCatalogEntry[]>([])
  const [currentUser, setCurrentUser] = useState<AuthUser | null>(null)
  const [isAuthReady, setIsAuthReady] = useState(false)

  useEffect(() => {
    let isMounted = true

    void fetchJson<LibraryCatalogEntry[]>(`${apiBaseUrl}/api/libraries`)
      .then((nextLibraries) => {
        if (!isMounted) {
          return
        }

        setLibraries(nextLibraries)
      })
      .catch(() => {
        if (!isMounted) {
          return
        }

        setLibraries([])
      })

    return () => {
      isMounted = false
    }
  }, [])

  useEffect(() => {
    let isMounted = true

    void fetchJson<AuthUser>(`${apiBaseUrl}/api/auth/me`)
      .then((user) => {
        if (!isMounted) {
          return
        }

        setCurrentUser(user)
      })
      .catch(() => {
        if (!isMounted) {
          return
        }

        setCurrentUser(null)
      })
      .finally(() => {
        if (!isMounted) {
          return
        }

        setIsAuthReady(true)
      })

    return () => {
      isMounted = false
    }
  }, [])

  const handleSignOut = async () => {
    try {
      await fetch(`${apiBaseUrl}/api/auth/logout`, {
        method: 'POST',
        credentials: 'include',
      })
    } finally {
      setCurrentUser(null)
    }
  }

  const data: AppData = { libraries }

  return (
    <Routes>
      <Route
        path="/"
        element={
          <LandingPage
            data={data}
            currentUser={currentUser}
            isAuthReady={isAuthReady}
            onLogoutClick={handleSignOut}
          />
        }
      />
      <Route
        path="/libraries"
        element={
          <CatalogPage
            data={data}
            currentUser={currentUser}
            isAuthReady={isAuthReady}
            onLogoutClick={handleSignOut}
          />
        }
      />
      <Route
        path="/libraries/:libraryId"
        element={
          <LibraryDetailPage
            data={data}
            currentUser={currentUser}
            isAuthReady={isAuthReady}
            onLogoutClick={handleSignOut}
          />
        }
      />
      <Route
        path="/login"
        element={
          <LoginPage
            data={data}
            currentUser={currentUser}
            isAuthReady={isAuthReady}
            onSignedIn={setCurrentUser}
            onLogoutClick={handleSignOut}
          />
        }
      />
      <Route
        path="/profile"
        element={
          <ProfilePage
            data={data}
            currentUser={currentUser}
            isAuthReady={isAuthReady}
            onUserUpdated={setCurrentUser}
            onSignedOut={handleSignOut}
          />
        }
      />
    </Routes>
  )
}

export default App
