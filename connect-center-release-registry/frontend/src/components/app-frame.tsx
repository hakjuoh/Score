import { useEffect, useRef, useState, type ReactNode } from 'react'
import { ChevronDown, LogOut, Search, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { getUserInitials, getUserRoles } from '@/lib/registry'
import type { AuthUser } from '@/types/registry'

type AppFrameProps = {
  children: ReactNode
  query: string
  suggestions: string[]
  onQueryChange: (value: string) => void
  onQuerySubmit: (value?: string) => void
  onHomeClick: () => void
  onLibraryClick: () => void
  activeNav?: 'libraries'
  currentUser: AuthUser | null
  isAuthReady: boolean
  onLoginClick: () => void
  onProfileClick: () => void
  onLogoutClick: () => Promise<void>
}

export function AppFrame({
  children,
  query,
  suggestions,
  onQueryChange,
  onQuerySubmit,
  onHomeClick,
  onLibraryClick,
  activeNav,
  currentUser,
  isAuthReady,
  onLoginClick,
  onProfileClick,
  onLogoutClick,
}: AppFrameProps) {
  const searchRef = useRef<HTMLFormElement | null>(null)
  const inputRef = useRef<HTMLInputElement | null>(null)
  const profileMenuRef = useRef<HTMLDivElement | null>(null)
  const [isSearchOpen, setIsSearchOpen] = useState(false)
  const [isProfileMenuOpen, setIsProfileMenuOpen] = useState(false)
  const [isLoggingOut, setIsLoggingOut] = useState(false)

  useEffect(() => {
    if (!isSearchOpen && !isProfileMenuOpen) {
      return
    }

    const handlePointerDown = (event: MouseEvent) => {
      if (!searchRef.current?.contains(event.target as Node)) {
        setIsSearchOpen(false)
      }

      if (!profileMenuRef.current?.contains(event.target as Node)) {
        setIsProfileMenuOpen(false)
      }
    }

    document.addEventListener('mousedown', handlePointerDown)
    return () => {
      document.removeEventListener('mousedown', handlePointerDown)
    }
  }, [isProfileMenuOpen, isSearchOpen])

  const normalizedQuery = query.trim().toLowerCase()
  const visibleSuggestions = normalizedQuery
    ? suggestions
        .filter((suggestion) => suggestion.toLowerCase().includes(normalizedQuery))
        .slice(0, 5)
    : suggestions.slice(0, 5)

  return (
    <main className="min-h-screen bg-background text-foreground">
      <header className="relative z-40 border-b border-border bg-background/95 backdrop-blur">
        <div className="relative flex min-h-[68px] w-full items-center gap-4 px-4 py-3 sm:px-6 lg:px-8">
          <button
            type="button"
            onClick={onHomeClick}
            className="flex shrink-0 cursor-pointer items-center gap-3 font-semibold"
          >
            <span>connectCenter Registry</span>
          </button>

          <nav className="pointer-events-none absolute left-1/2 top-0 flex h-full -translate-x-1/2 items-stretch">
            <button
              type="button"
              onClick={onLibraryClick}
              className={`pointer-events-auto flex items-center border-b-2 px-1 text-base font-medium transition-colors ${
                activeNav === 'libraries'
                  ? 'border-foreground text-foreground'
                  : 'border-transparent text-muted-foreground hover:bg-muted hover:text-foreground'
              }`}
            >
              Library
            </button>
          </nav>

          <div className="ml-auto flex min-w-0 flex-1 items-center justify-end gap-3">
            <div className="w-full md:max-w-md">
              <form
                ref={searchRef}
                className="relative z-50 w-full"
                onSubmit={(event) => {
                  event.preventDefault()
                  onQuerySubmit(query)
                  setIsSearchOpen(false)
                }}
              >
                <label className="flex h-11 w-full items-center gap-3 rounded-full border border-border bg-card px-4 text-sm text-muted-foreground">
                  <Search className="size-4 shrink-0" />
                  <input
                    ref={inputRef}
                    value={query}
                    onChange={(event) => onQueryChange(event.target.value)}
                    onFocus={() => setIsSearchOpen(true)}
                    onKeyDown={(event) => {
                      if (event.key === 'Escape') {
                        setIsSearchOpen(false)
                        setIsProfileMenuOpen(false)
                      }
                    }}
                    placeholder="Search libraries"
                    className="w-full border-0 bg-transparent p-0 text-foreground outline-none placeholder:text-muted-foreground"
                  />
                  {query ? (
                    <button
                      type="button"
                      aria-label="Clear search"
                      onClick={() => {
                        onQueryChange('')
                        inputRef.current?.focus()
                      }}
                      className="flex size-6 items-center justify-center rounded-full text-muted-foreground transition hover:bg-muted hover:text-foreground"
                    >
                      <X className="size-4" />
                    </button>
                  ) : null}
                </label>
                {isSearchOpen ? (
                  <div className="absolute left-0 right-0 top-full z-50 mt-2 overflow-hidden rounded-3xl border border-border bg-card shadow-[0_18px_50px_rgba(17,17,17,0.08)]">
                    {visibleSuggestions.length > 0 ? (
                      <div className="p-2">
                        {visibleSuggestions.map((suggestion) => (
                          <button
                            key={suggestion}
                            type="button"
                            onClick={() => {
                              onQueryChange(suggestion)
                              onQuerySubmit(suggestion)
                              setIsSearchOpen(false)
                            }}
                            className="flex w-full items-center justify-between rounded-2xl px-3 py-3 text-left text-sm text-foreground transition hover:bg-muted"
                          >
                            <span>{suggestion}</span>
                            <span className="text-xs text-muted-foreground">Search</span>
                          </button>
                        ))}
                      </div>
                    ) : (
                      <div className="px-4 py-5 text-sm text-muted-foreground">
                        No matching libraries. Press Enter to search libraries.
                      </div>
                    )}
                    <div className="border-t border-border px-4 py-3 text-xs text-muted-foreground">
                      Press Enter to search libraries.
                    </div>
                  </div>
                ) : null}
              </form>
            </div>

            {isAuthReady ? (
              currentUser ? (
                <div ref={profileMenuRef} className="relative shrink-0">
                  <button
                    type="button"
                    onClick={() => {
                      setIsProfileMenuOpen((current) => !current)
                      setIsSearchOpen(false)
                    }}
                    className="inline-flex h-11 items-center gap-3 rounded-full border border-border bg-card px-3 text-sm text-foreground transition hover:bg-muted"
                  >
                    <span className="inline-flex size-7 items-center justify-center rounded-full bg-foreground text-xs font-semibold text-background">
                      {getUserInitials(currentUser)}
                    </span>
                    <span className="max-w-[10rem] truncate">{currentUser.username}</span>
                    <ChevronDown className="size-4 text-muted-foreground" />
                  </button>
                  {isProfileMenuOpen ? (
                    <div className="absolute right-0 top-full z-50 mt-2 min-w-52 overflow-hidden rounded-3xl border border-border bg-card p-2 shadow-[0_18px_50px_rgba(17,17,17,0.08)]">
                      <button
                        type="button"
                        onClick={() => {
                          setIsProfileMenuOpen(false)
                          onProfileClick()
                        }}
                        className="flex w-full items-center gap-3 rounded-2xl px-3 py-2 text-left text-sm text-foreground transition hover:bg-muted"
                      >
                        <span className="inline-flex size-7 items-center justify-center rounded-full bg-foreground text-xs font-semibold text-background">
                          {getUserInitials(currentUser)}
                        </span>
                        <span className="min-w-0">
                          <span className="block truncate font-medium text-foreground">
                            {currentUser.displayName}
                          </span>
                          <span className="mt-0.5 block truncate text-xs text-muted-foreground">
                            {getUserRoles(currentUser).join(' • ')}
                          </span>
                        </span>
                      </button>
                      <button
                        type="button"
                        disabled={isLoggingOut}
                        onClick={async () => {
                          setIsLoggingOut(true)
                          try {
                            await onLogoutClick()
                          } finally {
                            setIsLoggingOut(false)
                            setIsProfileMenuOpen(false)
                          }
                        }}
                        className="mt-1 flex h-11 w-full items-center gap-3 rounded-2xl px-3 text-left text-sm text-foreground transition hover:bg-muted disabled:cursor-not-allowed disabled:opacity-60"
                      >
                        <span className="inline-flex size-7 items-center justify-center rounded-full border border-border text-muted-foreground">
                          <LogOut className="size-4" />
                        </span>
                        <span>{isLoggingOut ? 'Logging out...' : 'Log out'}</span>
                      </button>
                    </div>
                  ) : null}
                </div>
              ) : (
                <Button
                  type="button"
                  variant="outline"
                  className="h-11 shrink-0 rounded-full px-5"
                  onClick={onLoginClick}
                >
                  Log in
                </Button>
              )
            ) : (
              <div className="h-10 w-24 shrink-0 rounded-full border border-border bg-card/40" />
            )}
          </div>
        </div>
      </header>

      {children}
    </main>
  )
}
