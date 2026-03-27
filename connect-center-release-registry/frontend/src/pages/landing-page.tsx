import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { AppFrame } from '@/components/app-frame'
import {
  createCatalogUrl,
  createLibraryUrl,
  findExactLibraryMatch,
  getLibrarySuggestions,
} from '@/lib/registry'
import type { AppData, AuthUser } from '@/types/registry'

type LandingPageProps = {
  data: AppData
  currentUser: AuthUser | null
  isAuthReady: boolean
  onLogoutClick: () => Promise<void>
}

export function LandingPage({
  data,
  currentUser,
  isAuthReady,
  onLogoutClick,
}: LandingPageProps) {
  const navigate = useNavigate()
  const [searchInput, setSearchInput] = useState('')

  useEffect(() => {
    document.title = 'connectCenter Registry'
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
      onLoginClick={() => navigate('/login?next=/')}
      onProfileClick={() => navigate('/profile')}
      onLogoutClick={onLogoutClick}
    >
      <div className="flex min-h-[calc(100vh-68px)] items-center justify-center px-4 py-16 text-center sm:px-6 lg:px-8">
        <h1 className="max-w-4xl text-4xl font-semibold tracking-tight sm:text-6xl">
          connectCenter Registry
        </h1>
      </div>
    </AppFrame>
  )
}
