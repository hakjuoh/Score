import type { AuthUser, LibraryCatalogEntry } from '@/types/registry'

export const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? ''

export function formatUtc(value: string | null) {
  if (!value) {
    return 'Not available yet'
  }

  return new Intl.DateTimeFormat('en-US', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'UTC',
  }).format(new Date(value))
}

export function formatUtcDate(value: string | null) {
  if (!value) {
    return 'Not available yet'
  }

  return new Intl.DateTimeFormat('en-US', {
    dateStyle: 'medium',
    timeZone: 'UTC',
  }).format(new Date(value))
}

export function getNamespaceValues(namespaceUri: string | null) {
  if (!namespaceUri) {
    return []
  }

  return namespaceUri
    .split(/[\n,;]+/)
    .map((value) => value.trim())
    .filter(Boolean)
}

export function createCatalogUrl(query: string) {
  const params = new URLSearchParams()
  const trimmed = query.trim()
  if (trimmed) {
    params.set('q', trimmed)
  }

  const search = params.toString()
  return search ? `/libraries?${search}` : '/libraries'
}

export function createLibraryUrl(libraryId: number, query: string) {
  const catalogUrl = createCatalogUrl(query)
  return catalogUrl === '/libraries'
    ? `/libraries/${libraryId}`
    : `/libraries/${libraryId}${catalogUrl.slice('/libraries'.length)}`
}

export function findExactLibraryMatch(libraries: LibraryCatalogEntry[], query: string) {
  const normalizedQuery = query.trim().toLowerCase()
  if (!normalizedQuery) {
    return null
  }

  const matches = libraries.filter(
    (library) => library.name.trim().toLowerCase() === normalizedQuery,
  )

  return matches.length === 1 ? matches[0] : null
}

export async function fetchJson<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    credentials: 'include',
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers ?? {}),
    },
  })

  if (!response.ok) {
    let message = `Failed to fetch ${url}`
    if (response.status === 404 && url.includes('/api/auth/')) {
      message = 'Authentication service is unavailable. Restart the backend server.'
    }
    try {
      const contentType = response.headers.get('content-type') ?? ''
      if (contentType.includes('application/json')) {
        const payload = (await response.json()) as { error?: string }
        if (payload.error) {
          message = payload.error
        }
      } else {
        const text = await response.text()
        if (text.trim()) {
          message = text
        }
      }
    } catch {
      // Keep fallback message.
    }

    throw new Error(message)
  }

  return response.json() as Promise<T>
}

export async function downloadExportData(exportUrl: string, exportFileName: string) {
  const response = await fetch(exportUrl, {
    credentials: 'include',
  })

  if (!response.ok) {
    const statusMessage =
      response.status === 404
        ? 'Data is not available for this release.'
        : response.status === 403
          ? 'You do not have access to this data.'
          : `Failed to download ${exportFileName}.`
    throw new Error(statusMessage)
  }

  const blob = await response.blob()
  const objectUrl = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = exportFileName
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(objectUrl)
}

export function getUserInitials(user: AuthUser) {
  const source = user.displayName || user.username
  const firstCharacter = source.trim().charAt(0) || user.username.trim().charAt(0)
  return firstCharacter.toUpperCase()
}

export function getUserRoles(user: AuthUser) {
  const roles: string[] = []
  if (user.isAdmin) {
    roles.push('Admin')
  }
  if (user.isDeveloper) {
    roles.push('Developer')
  }

  return roles.length > 0 ? roles : ['User']
}

export function getLibrarySuggestions(libraries: LibraryCatalogEntry[]) {
  return [...new Set(libraries.map((library) => library.name))].sort((left, right) =>
    left.localeCompare(right),
  )
}
