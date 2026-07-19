<script lang="ts">
  import { onMount } from "svelte";
  import { page } from "$app/stores";
  import { goto } from "$app/navigation";

  /**
   * Deep-link handler per 60-apps/CLAUDE.md §AT URI Deep-Link Routing.
   * /at/{authority}/{collection}/{rkey}  ⇔  at://{authority}/{collection}/{rkey}
   * For lawfirm records we rewrite to the matter detail route.
   */
  onMount(() => {
    const parts = ($page.params.rest ?? "").split("/");
    const [authority, collection, rkey] = [parts[0], parts[1], parts.slice(2).join("/")];
    if (collection === "com.etzhayyim.apps.lawfirm.matter" && authority && rkey) {
      goto(`/m/${rkey}?firm=${encodeURIComponent(authority)}`, { replaceState: true });
      return;
    }
    if (collection === "com.etzhayyim.apps.lawfirm.legalDocument" && authority && rkey) {
      // Documents nest under matter; rkey is the doc hash, parent matter needs resolution.
      goto(`/?firm=${encodeURIComponent(authority)}&doc=${encodeURIComponent(rkey)}`, { replaceState: true });
      return;
    }
    // Unknown collection — keep user on /at path with a hint.
  });
</script>

<div class="text-sm text-neutral-500">
  Resolving <code class="font-mono">at://{$page.params.rest}</code>…
</div>
