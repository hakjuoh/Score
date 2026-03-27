import { useEffect, useState } from 'react'
import { Navigate, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { ArrowLeft, FileDown, FolderOpen, House, X } from 'lucide-react'
import { AppFrame } from '@/components/app-frame'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import {
  apiBaseUrl,
  createCatalogUrl,
  createLibraryUrl,
  downloadExportData,
  fetchJson,
  findExactLibraryMatch,
  formatUtcDate,
  getLibrarySuggestions,
  getNamespaceValues,
} from '@/lib/registry'
import type {
  AppData,
  AuthUser,
  DetailData,
  DownloadNotification,
  LibraryCatalogEntry,
  ReleaseRecord,
} from '@/types/registry'

type LibraryDetailPageProps = {
  data: AppData
  currentUser: AuthUser | null
  isAuthReady: boolean
  onLogoutClick: () => Promise<void>
}

export function LibraryDetailPage({
  data,
  currentUser,
  isAuthReady,
  onLogoutClick,
}: LibraryDetailPageProps) {
  const navigate = useNavigate()
  const location = useLocation()
  const params = useParams()
  const [searchParams] = useSearchParams()
  const urlQuery = searchParams.get('q') ?? ''
  const selectedLibraryId = Number(params.libraryId)
  const [searchInput, setSearchInput] = useState(urlQuery)
  const [detail, setDetail] = useState<DetailData>({
    selectedLibrary:
      data.libraries.find((library) => library.libraryId === selectedLibraryId) ?? null,
    selectedReleases: [],
  })
  const [downloadingReleaseId, setDownloadingReleaseId] = useState<number | null>(null)
  const [downloadNotifications, setDownloadNotifications] = useState<DownloadNotification[]>([])
  const firstDownloadNotificationId = downloadNotifications[0]?.id

  useEffect(() => {
    document.title = detail.selectedLibrary
      ? `connectCenter Registry | ${detail.selectedLibrary.name}`
      : 'connectCenter Registry'
  }, [detail.selectedLibrary])

  useEffect(() => {
    setSearchInput(urlQuery)
  }, [urlQuery])

  useEffect(() => {
    if (!firstDownloadNotificationId) {
      return
    }

    const timeoutId = window.setTimeout(() => {
      setDownloadNotifications((current) => current.slice(1))
    }, 4000)

    return () => {
      window.clearTimeout(timeoutId)
    }
  }, [firstDownloadNotificationId])

  useEffect(() => {
    if (!Number.isFinite(selectedLibraryId)) {
      return
    }

    let isMounted = true

    void Promise.all([
      fetchJson<LibraryCatalogEntry>(`${apiBaseUrl}/api/libraries/${selectedLibraryId}`),
      fetchJson<ReleaseRecord[]>(`${apiBaseUrl}/api/libraries/${selectedLibraryId}/releases`),
    ])
      .then(([selectedLibrary, selectedReleases]) => {
        if (!isMounted) {
          return
        }

        setDetail({ selectedLibrary, selectedReleases })
      })
      .catch(() => {
        if (!isMounted) {
          return
        }

        setDetail({
          selectedLibrary:
            data.libraries.find((library) => library.libraryId === selectedLibraryId) ?? null,
          selectedReleases: [],
        })
      })

    return () => {
      isMounted = false
    }
  }, [data.libraries, selectedLibraryId])

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

  const backToCatalog = () => {
    navigate(createCatalogUrl(urlQuery))
  }

  const backToCatalogCleared = () => {
    navigate('/libraries')
  }

  const selectedLibrary = detail.selectedLibrary
  const selectedLibraryLink = selectedLibrary?.link?.trim() ?? ''

  const pushDownloadNotification = (message: string) => {
    const id = Date.now() + Math.floor(Math.random() * 1000)
    setDownloadNotifications((current) => [...current, { id, message }])
  }

  if (!Number.isFinite(selectedLibraryId)) {
    return <Navigate to={createCatalogUrl(urlQuery)} replace />
  }

  if (!selectedLibrary) {
    return (
      <AppFrame
        query={searchInput}
        suggestions={getLibrarySuggestions(data.libraries)}
        onQueryChange={setSearchInput}
        onQuerySubmit={submitQuery}
        onHomeClick={() => navigate('/')}
        onLibraryClick={backToCatalog}
        activeNav="libraries"
        currentUser={currentUser}
        isAuthReady={isAuthReady}
        onLoginClick={() =>
          navigate(`/login?next=${encodeURIComponent(location.pathname + location.search)}`)
        }
        onProfileClick={() => navigate('/profile')}
        onLogoutClick={onLogoutClick}
      >
        <div className="mx-auto flex max-w-7xl flex-col gap-6 px-4 py-8 sm:px-6 lg:px-8">
          <button
            type="button"
            onClick={backToCatalogCleared}
            className="inline-flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground"
          >
            <ArrowLeft className="size-4" />
            Libraries
          </button>
          <div className="rounded-3xl border border-border bg-card px-6 py-10 text-muted-foreground">
            Library not found for <code>{location.pathname}</code>.
          </div>
        </div>
      </AppFrame>
    )
  }

  return (
    <AppFrame
      query={searchInput}
      suggestions={getLibrarySuggestions(data.libraries)}
      onQueryChange={setSearchInput}
      onQuerySubmit={submitQuery}
      onHomeClick={() => navigate('/')}
      onLibraryClick={backToCatalog}
      activeNav="libraries"
      currentUser={currentUser}
      isAuthReady={isAuthReady}
      onLoginClick={() =>
        navigate(`/login?next=${encodeURIComponent(location.pathname + location.search)}`)
      }
      onProfileClick={() => navigate('/profile')}
      onLogoutClick={onLogoutClick}
    >
      {downloadNotifications.length > 0 ? (
        <div className="fixed right-6 top-24 z-[70] flex w-full max-w-sm flex-col gap-3">
          {downloadNotifications.map((notification) => (
            <Card
              key={notification.id}
              className="rounded-2xl border-border shadow-[0_18px_50px_rgba(17,17,17,0.08)]"
            >
              <CardHeader className="flex flex-row items-start justify-between gap-4 p-4">
                <div className="space-y-1">
                  <CardTitle className="text-base">Download unavailable</CardTitle>
                  <CardDescription>{notification.message}</CardDescription>
                </div>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="size-8 rounded-full"
                  onClick={() =>
                    setDownloadNotifications((current) =>
                      current.filter((item) => item.id !== notification.id),
                    )
                  }
                  aria-label="Dismiss download error"
                >
                  <X className="size-4" />
                </Button>
              </CardHeader>
            </Card>
          ))}
        </div>
      ) : null}

      <div className="mx-auto flex max-w-7xl flex-col gap-8 px-4 py-8 sm:px-6 lg:px-8">
        <nav className="flex items-center gap-2 text-sm text-muted-foreground">
          <button
            type="button"
            onClick={backToCatalogCleared}
            className="inline-flex items-center gap-2 hover:text-foreground"
          >
            <ArrowLeft className="size-4" />
            Libraries
          </button>
          <span>/</span>
          <span className="text-foreground">{selectedLibrary.name}</span>
        </nav>

        <section className="space-y-4">
          <div className="space-y-3">
            <h1 className="text-4xl font-semibold tracking-tight sm:text-5xl">
              {selectedLibrary.name}
            </h1>
            <p className="w-full text-lg leading-8 text-muted-foreground">
              {selectedLibrary.description}
            </p>
          </div>
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <FolderOpen className="size-4" />
            <span>{selectedLibrary.organization}</span>
            {selectedLibraryLink ? (
              <a
                href={selectedLibraryLink}
                target="_blank"
                rel="noreferrer"
                aria-label={`${selectedLibrary.organization} homepage`}
                title={`${selectedLibrary.organization} homepage`}
                className="inline-flex size-6 items-center justify-center rounded-full text-foreground transition hover:bg-muted"
              >
                <House className="size-4" />
              </a>
            ) : null}
            <span aria-hidden="true">·</span>
            <span>{selectedLibrary.domain}</span>
          </div>
        </section>

        <section className="space-y-4">
          <div className="flex items-center justify-between gap-4">
            <h2 className="text-2xl font-semibold">Releases</h2>
            <Badge variant="outline">{detail.selectedReleases.length} records</Badge>
          </div>

          <Card className="overflow-hidden rounded-3xl border-border">
            <div className="overflow-x-auto">
              <Table className="min-w-[960px] table-fixed">
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-[10rem]">Release</TableHead>
                    <TableHead className="w-[20rem]">Dependencies</TableHead>
                    <TableHead className="w-[24rem]">Namespace</TableHead>
                    <TableHead className="w-[12rem]">Last Updated</TableHead>
                    <TableHead className="w-[10rem] text-center">Data</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {detail.selectedReleases.length > 0 ? (
                    detail.selectedReleases.map((release) => (
                      <TableRow key={release.releaseId}>
                        <TableCell className="w-[10rem]">
                          <div className="flex items-center gap-2">
                            <span className="font-medium">{release.releaseNum}</span>
                            {release.isLatestRelease ? <Badge variant="outline">Latest</Badge> : null}
                          </div>
                        </TableCell>
                        <TableCell className="w-[20rem]">
                          {release.dependencies.length > 0 ? (
                            <div className="space-y-2">
                              <div className="flex flex-wrap gap-2">
                                {release.dependencies.map((dependency) => (
                                  <button
                                    key={`${release.releaseId}-${dependency.releaseId}`}
                                    type="button"
                                    onClick={() =>
                                      navigate(createLibraryUrl(dependency.libraryId, urlQuery))
                                    }
                                    className="inline-flex items-center rounded-full border border-border px-2.5 py-0.5 text-xs font-medium text-foreground transition hover:border-foreground/50 hover:bg-muted"
                                  >
                                    {dependency.libraryName}
                                    <span className="px-1 text-muted-foreground" aria-hidden="true">
                                      ·
                                    </span>
                                    {dependency.releaseNum}
                                  </button>
                                ))}
                              </div>
                            </div>
                          ) : (
                            <span className="text-sm text-muted-foreground">No dependencies</span>
                          )}
                        </TableCell>
                        <TableCell className="w-[24rem]">
                          {getNamespaceValues(release.namespaceUri).length > 0 ? (
                            <div className="space-y-1">
                              {getNamespaceValues(release.namespaceUri).map((namespaceValue) => (
                                <div key={`${release.releaseId}-${namespaceValue}`}>{namespaceValue}</div>
                              ))}
                            </div>
                          ) : (
                            <div>No namespace</div>
                          )}
                        </TableCell>
                        <TableCell className="w-[12rem]">
                          <div>{formatUtcDate(release.lastUpdatedAtUtc)}</div>
                        </TableCell>
                        <TableCell className="w-[10rem] text-center">
                          <button
                            type="button"
                            aria-label={`Download ${release.exportFileName}`}
                            title={release.exportFileName}
                            onClick={async () => {
                              try {
                                setDownloadingReleaseId(release.releaseId)
                                await downloadExportData(release.exportUrl, release.exportFileName)
                              } catch (error) {
                                console.error(error)
                                pushDownloadNotification(
                                  error instanceof Error
                                    ? error.message
                                    : `Failed to download ${release.exportFileName}.`,
                                )
                              } finally {
                                setDownloadingReleaseId(null)
                              }
                            }}
                            className="inline-flex size-8 items-center justify-center rounded-full text-foreground transition hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
                            disabled={downloadingReleaseId === release.releaseId}
                          >
                            <FileDown className="size-4" />
                          </button>
                        </TableCell>
                      </TableRow>
                    ))
                  ) : (
                    <TableRow>
                      <TableCell colSpan={5} className="px-6 py-10 text-center text-sm text-muted-foreground">
                        No releases are available for this library.
                      </TableCell>
                    </TableRow>
                  )}
                </TableBody>
              </Table>
            </div>
          </Card>
        </section>
      </div>
    </AppFrame>
  )
}
