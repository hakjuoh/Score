import { useEffect, useState } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { AppFrame } from '@/components/app-frame'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  apiBaseUrl,
  createCatalogUrl,
  createLibraryUrl,
  fetchJson,
  findExactLibraryMatch,
  getLibrarySuggestions,
} from '@/lib/registry'
import type { AppData, AuthUser } from '@/types/registry'

type ProfilePageProps = {
  data: AppData
  currentUser: AuthUser | null
  isAuthReady: boolean
  onUserUpdated: (user: AuthUser | null) => void
  onSignedOut: () => Promise<void>
}

export function ProfilePage({
  data,
  currentUser,
  isAuthReady,
  onUserUpdated,
  onSignedOut,
}: ProfilePageProps) {
  const navigate = useNavigate()
  const location = useLocation()
  const [searchInput, setSearchInput] = useState('')
  const [displayName, setDisplayName] = useState(currentUser?.displayName ?? '')
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)
  const [isSaving, setIsSaving] = useState(false)

  useEffect(() => {
    document.title = 'connectCenter Registry | Profile'
  }, [])

  useEffect(() => {
    setDisplayName(currentUser?.displayName ?? '')
  }, [currentUser?.displayName])

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

  if (isAuthReady && !currentUser) {
    return <Navigate to={`/login?next=${encodeURIComponent(location.pathname)}`} replace />
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
      onLoginClick={() => navigate(`/login?next=${encodeURIComponent(location.pathname)}`)}
      onProfileClick={() => navigate('/profile')}
      onLogoutClick={onSignedOut}
    >
      <div className="mx-auto flex max-w-7xl flex-col gap-8 px-4 py-8 sm:px-6 lg:px-8">
        <div className="space-y-3">
          <h1 className="text-4xl font-semibold tracking-tight">Profile</h1>
          <p className="text-lg text-muted-foreground">
            Update your display name and password for the registry.
          </p>
        </div>

        <Card className="max-w-2xl rounded-[1.8rem] border-border">
          <CardHeader>
            <CardTitle>Account settings</CardTitle>
            <CardDescription>
              Username is fixed. Current password is required to save changes.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-5">
            <form
              className="space-y-4"
              onSubmit={async (event) => {
                event.preventDefault()
                setErrorMessage(null)
                setSuccessMessage(null)
                setIsSaving(true)

                try {
                  const user = await fetchJson<AuthUser>(`${apiBaseUrl}/api/auth/profile`, {
                    method: 'PATCH',
                    body: JSON.stringify({
                      displayName,
                      currentPassword,
                      newPassword: newPassword.trim() ? newPassword : null,
                    }),
                  })
                  onUserUpdated(user)
                  setCurrentPassword('')
                  setNewPassword('')
                  setSuccessMessage('Profile updated.')
                } catch (error) {
                  setErrorMessage(
                    error instanceof Error ? error.message : 'Unable to update your profile.',
                  )
                } finally {
                  setIsSaving(false)
                }
              }}
            >
              <label className="flex flex-col gap-2 text-sm">
                <span className="font-medium">Username</span>
                <input
                  value={currentUser?.username ?? ''}
                  readOnly
                  disabled
                  className="cursor-not-allowed rounded-2xl border border-border bg-muted px-4 py-3 text-muted-foreground outline-none"
                />
              </label>
              <label className="flex flex-col gap-2 text-sm">
                <span className="font-medium">Display Name</span>
                <input
                  value={displayName}
                  onChange={(event) => setDisplayName(event.target.value)}
                  className="rounded-2xl border border-border bg-background px-4 py-3 outline-none transition focus:border-foreground"
                />
              </label>
              <label className="flex flex-col gap-2 text-sm">
                <span className="font-medium">Current password</span>
                <input
                  type="password"
                  value={currentPassword}
                  onChange={(event) => setCurrentPassword(event.target.value)}
                  className="rounded-2xl border border-border bg-background px-4 py-3 outline-none transition focus:border-foreground"
                />
              </label>
              <label className="flex flex-col gap-2 text-sm">
                <span className="font-medium">New password</span>
                <input
                  type="password"
                  value={newPassword}
                  onChange={(event) => setNewPassword(event.target.value)}
                  className="rounded-2xl border border-border bg-background px-4 py-3 outline-none transition focus:border-foreground"
                  placeholder="Leave blank to keep the current password"
                />
              </label>
              {errorMessage ? (
                <div className="rounded-2xl border border-destructive/20 bg-destructive/5 px-4 py-3 text-sm text-destructive">
                  {errorMessage}
                </div>
              ) : null}
              {successMessage ? (
                <div className="rounded-2xl border border-border bg-muted px-4 py-3 text-sm text-foreground">
                  {successMessage}
                </div>
              ) : null}
              <div className="flex flex-wrap gap-3">
                <Button type="submit" disabled={isSaving}>
                  {isSaving ? 'Saving...' : 'Save changes'}
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>
      </div>
    </AppFrame>
  )
}
