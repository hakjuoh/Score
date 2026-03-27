export type LibraryCatalogEntry = {
  libraryId: number
  name: string
  organization: string
  link: string
  domain: string
  description: string
  releaseCount: number
  latestReleaseNum: string | null
  latestLastUpdatedAtUtc: string | null
}

export type ReleaseDependency = {
  releaseId: number
  libraryId: number
  libraryName: string
  releaseNum: string
}

export type ReleaseRecord = {
  releaseId: number
  releaseNum: string
  namespaceUri: string | null
  lastUpdatedAtUtc: string
  exportFileName: string
  exportUrl: string
  isLatestRelease: boolean
  dependencies: ReleaseDependency[]
}

export type AuthUser = {
  appUserId: number
  username: string
  displayName: string
  isAdmin: boolean
  isDeveloper: boolean
}

export type AppData = {
  libraries: LibraryCatalogEntry[]
}

export type DetailData = {
  selectedLibrary: LibraryCatalogEntry | null
  selectedReleases: ReleaseRecord[]
}

export type DownloadNotification = {
  id: number
  message: string
}
