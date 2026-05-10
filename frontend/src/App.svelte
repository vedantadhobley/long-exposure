<script>
  // Day 1 stub. Renders a single-line placeholder so the dev container
  // can mount and the docker-compose end-to-end smoke test passes.
  let status = $state('loading');

  async function checkApi() {
    try {
      const r = await fetch('/api/v1/health');
      const j = await r.json();
      status = j.status ?? 'unknown';
    } catch (e) {
      status = 'unreachable';
    }
  }
  checkApi();
</script>

<main>
  <h1>Long Exposure</h1>
  <p>API: {status}</p>
</main>

<style>
  :global(html, body) {
    margin: 0;
    background: #0a0a0a;
    color: #f0f0f0;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  }
  main {
    padding: 4rem 2rem;
    max-width: 720px;
    margin: 0 auto;
  }
  h1 {
    font-weight: 300;
    letter-spacing: -0.02em;
  }
</style>
