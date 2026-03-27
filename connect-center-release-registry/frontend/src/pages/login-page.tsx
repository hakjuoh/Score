import { useEffect, useState } from 'react'
import { Navigate, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { AppFrame } from '@/components/app-frame'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import {
  apiBaseUrl,
  createCatalogUrl,
  createLibraryUrl,
  fetchJson,
  findExactLibraryMatch,
  getLibrarySuggestions,
} from '@/lib/registry'
import type { AppData, AuthUser } from '@/types/registry'

type LoginPageProps = {
  data: AppData
  currentUser: AuthUser | null
  isAuthReady: boolean
  onSignedIn: (user: AuthUser | null) => void
  onLogoutClick: () => Promise<void>
}

export function LoginPage({
  data,
  currentUser,
  isAuthReady,
  onSignedIn,
  onLogoutClick,
}: LoginPageProps) {
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const [searchInput, setSearchInput] = useState('')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const nextUrl = searchParams.get('next') || '/libraries'

  useEffect(() => {
    document.title = 'connectCenter Registry | Log in'
  }, [])

  const submitQuery = (submittedValue?: string) => {
    const trimmed = (submittedValue ?? searchInput).trim()
    setSearchInput(trimmed)

    const exactMatch = findExactLibraryMatch(data.libraries, trimmed)
    if (exactMatch) {
      navigate(createLibraryUrl(exactMatch.libraryId, trimmed))
      return
    }

    navigate(createCatalogUrl(trimmed))
  }

  if (isAuthReady && currentUser) {
    return <Navigate to={nextUrl} replace />
  }

  return (
    <AppFrame
      query={searchInput}
      suggestions={getLibrarySuggestions(data.libraries)}
      onQueryChange={setSearchInput}
      onQuerySubmit={submitQuery}
      onHomeClick={() => navigate('/')}
      onLibraryClick={() => navigate('/libraries')}
      currentUser={currentUser}
      isAuthReady={isAuthReady}
      onLoginClick={() =>
        navigate(`/login?next=${encodeURIComponent(location.pathname + location.search)}`)
      }
      onProfileClick={() => navigate('/profile')}
      onLogoutClick={onLogoutClick}
    >
      <div className="mx-auto flex min-h-[calc(100vh-68px)] max-w-7xl items-center justify-center px-4 py-16 sm:px-6 lg:px-8">
        <Card className="w-full max-w-md rounded-[1.8rem] border-border">
          <CardHeader>
            <CardTitle className="text-2xl">Log in</CardTitle>
            <CardDescription>Sign in to manage your registry profile.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-5">
            <form
              className="space-y-4"
              autoComplete="off"
              onSubmit={async (event) => {
                event.preventDefault()
                setErrorMessage(null)
                setIsSubmitting(true)

                try {
                  const user = await fetchJson<AuthUser>(`${apiBaseUrl}/api/auth/login`, {
                    method: 'POST',
                    body: JSON.stringify({ username, password }),
                  })
                  onSignedIn(user)
                  navigate(nextUrl, { replace: true })
                } catch (error) {
                  setErrorMessage(
                    error instanceof Error ? error.message : 'Unable to log in with those credentials.',
                  )
                } finally {
                  setIsSubmitting(false)
                }
              }}
            >
              <label className="flex flex-col gap-2 text-sm">
                <span className="font-medium">Username</span>
                <input
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  autoComplete="off"
                  className="rounded-2xl border border-border bg-background px-4 py-3 outline-none transition focus:border-foreground"
                />
              </label>
              <label className="flex flex-col gap-2 text-sm">
                <span className="font-medium">Password</span>
                <input
                  type="password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  autoComplete="new-password"
                  className="rounded-2xl border border-border bg-background px-4 py-3 outline-none transition focus:border-foreground"
                />
              </label>
              {errorMessage ? (
                <div className="rounded-2xl border border-destructive/20 bg-destructive/5 px-4 py-3 text-sm text-destructive">
                  {errorMessage}
                </div>
              ) : null}
              <Button type="submit" className="w-full" disabled={isSubmitting}>
                {isSubmitting ? 'Signing in...' : 'Log in'}
              </Button>
            </form>
          </CardContent>
        </Card>
      </div>
    </AppFrame>
  )
}
