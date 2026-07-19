<script lang="ts">
  import "../app.css";
  import { page } from "$app/stores";
  import type { Snippet } from "svelte";

  interface Props { children?: Snippet }
  const { children }: Props = $props();

  const BASE_TITLE = "lawfirm.etzhayyim.com — AI Legal Services for India";
  const BASE_DESC = "AI-powered legal platform serving India in Hindi and 21 regional languages. NI Act 138, RERA, FEMA, PIL/RTI. Instant intake, auto-route to specialist counsel.";

  const TABS = [
    { id: "live",     label: "Live",     href: "/" },
    { id: "talk",     label: "Talk",     href: "/talk" },
    { id: "vibes",    label: "Vibes",    href: "/vibes" },
    { id: "provider", label: "Provider", href: "/provider" },
  ] as const;

  const activeId = $derived.by(() => {
    const path = $page.url.pathname;
    if (path.startsWith("/talk"))     return "talk";
    if (path.startsWith("/vibes"))    return "vibes";
    if (path.startsWith("/provider")) return "provider";
    return "live";
  });
</script>

<svelte:head>
  <title>{BASE_TITLE}</title>
  <meta name="description" content={BASE_DESC} />
  <meta property="og:title" content={BASE_TITLE} />
  <meta property="og:description" content={BASE_DESC} />
  <meta property="og:url" content={$page.url.href} />
</svelte:head>

<div class="flex flex-col min-h-screen">
  <header class="border-b border-neutral-200 dark:border-neutral-800 bg-white/80 dark:bg-neutral-950/80 backdrop-blur sticky top-0 z-10">
    <div class="max-w-screen-2xl mx-auto flex items-center gap-4 px-4 h-12">
      <a href="/" class="flex items-center gap-2">
        <span class="text-lg">⚖️</span>
        <span class="font-semibold text-sm">lawfirm.etzhayyim.com</span>
      </a>
      <nav class="flex items-center gap-1 ml-4">
        {#each TABS as t}
          <a href={t.href}
             class="px-3 py-1.5 text-sm rounded-md transition
                    {activeId === t.id
                      ? 'bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900'
                      : 'text-neutral-600 dark:text-neutral-400 hover:bg-neutral-100 dark:hover:bg-neutral-800'}">
            {t.label}
          </a>
        {/each}
      </nav>
      <div class="flex-1"></div>
      <div class="text-[10px] text-neutral-500 font-mono">ADR-0029 · recursive did:etzhayyim</div>
    </div>
  </header>

  <main class="flex-1 max-w-screen-2xl w-full mx-auto px-4 py-4">
    {@render children?.()}
  </main>
</div>
