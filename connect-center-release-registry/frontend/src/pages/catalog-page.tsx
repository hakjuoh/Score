import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { FolderOpen } from 'lucide-react'
import { AppFrame } from '@/components/app-frame'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  apiBaseUrl,
  createCatalogUrl,
  createLibraryUrl,
  fetchJson,
  findExactLibraryMatch,
  formatUtc,
  getLibrarySuggestions,
} from '@/lib/registry'
import type { AppData, AuthUser, LibraryCatalogEntry } from '@/types/registry'

type CatalogPageProps = {
  data: AppData
  currentUser: AuthUser | null
  isAuthReady: boolean
  onLogoutClick: () => Promise<void>
}

export function CatalogPage({
  data,
  currentUser,
  isAuthReady,
  onLogoutClick,
}: CatalogPageProps) {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const urlQuery = searchParams.get('q') ?? ''
  const [catalogLibraries, setCatalogLibraries] = useState<LibraryCatalogEntry[]>(data.libraries)
  const [searchInput, setSearchInput] = useState(urlQuery)

  useEffect(() => {
    document.title = 'connectCenter Registry'
  }, [])

  useEffect(() => {
    setSearchInput(urlQuery)
  }, [urlQuery])

  useEffect(() => {
    let isMounted = true

    void fetchJson<LibraryCatalogEntry[]>(
      `${apiBaseUrl}/api/libraries${urlQuery ? `?q=${encodeURIComponent(urlQuery)}` : ''}`,
    )
      .then((libraries) => {
        if (!isMounted) {
          return
        }

        setCatalogLibraries(libraries)
      })
      .catch(() => {
        if (!isMounted) {
          return
        }

        setCatalogLibraries([])
      })

    return () => {
      isMounted = false
    }
  }, [urlQuery])

  const submitQuery = (submittedValue?: string) => {
    const next = new URLSearchParams(searchParams)
    const trimmed = (submittedValue ?? searchInput).trim()

    setSearchInput(trimmed)

    if (!trimmed) {
      next.delete('q')
      setSearchParams(next, { replace: false })
      return
    }

    const exactMatch = findExactLibraryMatch(data.libraries, trimmed)
    if (exactMatch) {
      navigate(createLibraryUrl(exactMatch.libraryId, trimmed))
      return
    }

    next.set('q', trimmed)
    setSearchParams(next, { replace: false })
  }

  return (
    <AppFrame
      query={searchInput}
      suggestions={getLibrarySuggestions(data.libraries)}
      onQueryChange={setSearchInput}
      onQuerySubmit={submitQuery}
      onHomeClick={() => navigate('/')}
      onLibraryClick={() => navigate(createCatalogUrl(searchInput))}
      activeNav="libraries"
      currentUser={currentUser}
      isAuthReady={isAuthReady}
      onLoginClick={() =>
        navigate(`/login?next=${encodeURIComponent(createCatalogUrl(searchInput))}`)
      }
      onProfileClick={() => navigate('/profile')}
      onLogoutClick={onLogoutClick}
    >
      <div className="mx-auto flex max-w-7xl flex-col gap-8 px-4 py-8 sm:px-6 lg:px-8">
        <section className="space-y-4">
          <div className="space-y-3">
            <h1 className="text-4xl font-semibold tracking-tight sm:text-5xl">
              Browse libraries
            </h1>
            <p className="text-lg leading-8 text-muted-foreground">
              Explore the available libraries and open each one to review its releases,
              dependencies, and namespaces.
            </p>
          </div>
        </section>

        <section className="grid items-stretch gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {catalogLibraries.length > 0 ? (
            catalogLibraries.map((library) => (
              <button
                key={library.libraryId}
                type="button"
                onClick={() => navigate(createLibraryUrl(library.libraryId, searchInput))}
                className="h-full text-left"
              >
                <Card className="catalog-library-card flex h-full flex-col rounded-[1.6rem] border-border transition hover:-translate-y-0.5 hover:border-foreground/50">
                  <CardHeader className="catalog-library-header space-y-3 px-5 pb-3 pt-5">
                    <div className="min-w-0 space-y-2">
                      <CardTitle className="catalog-library-title text-[1.05rem] leading-7">
                        {library.name}
                      </CardTitle>
                      <div className="catalog-library-meta-line flex items-center gap-2 text-sm text-muted-foreground">
                        <FolderOpen className="size-4 shrink-0" />
                        <span>
                          {library.organization} · {library.domain}
                        </span>
                      </div>
                      <CardDescription className="catalog-library-description leading-5">
                        {library.description}
                      </CardDescription>
                    </div>
                  </CardHeader>
                  <CardContent className="mt-auto px-5 pb-5 pt-0 text-sm text-muted-foreground">
                    <div className="catalog-library-bottom flex min-h-[7.75rem] flex-col justify-end gap-4">
                      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
                        <span>{library.releaseCount} releases</span>
                      </div>
                      <div className="flex items-center justify-between gap-4 border-t border-border pt-4">
                        <div>
                          <div className="text-xs uppercase tracking-[0.16em]">Latest</div>
                          <div className="mt-1 font-medium text-foreground">
                            {library.latestReleaseNum ?? 'Unavailable'}
                          </div>
                        </div>
                        <div className="text-right text-xs text-muted-foreground">
                          {formatUtc(library.latestLastUpdatedAtUtc)}
                        </div>
                      </div>
                    </div>
                  </CardContent>
                </Card>
              </button>
            ))
          ) : (
            <Card className="col-span-full rounded-[1.6rem] border-border">
              <CardContent className="px-5 py-10 text-sm text-muted-foreground">
                No libraries are available.
              </CardContent>
            </Card>
          )}
        </section>
      </div>
    </AppFrame>
  )
}
