const STORAGE_KEY = "song-ladder-state";
const BASE_RATING = 1200;
const K_FACTOR = 32;

const sampleSongs = [
  { title: "Dreams", artist: "Fleetwood Mac", album: "Rumours" },
  { title: "Shine On You Crazy Diamond", artist: "Pink Floyd", album: "Wish You Were Here" },
  { title: "Nights", artist: "Frank Ocean", album: "Blonde" },
  { title: "All Too Well", artist: "Taylor Swift", album: "Red" },
  { title: "Superstition", artist: "Stevie Wonder", album: "Talking Book" },
  { title: "Hey Ya!", artist: "Outkast", album: "Speakerboxxx/The Love Below" }
];

const elements = {
  songForm: document.getElementById("songForm"),
  titleInput: document.getElementById("titleInput"),
  artistInput: document.getElementById("artistInput"),
  albumInput: document.getElementById("albumInput"),
  seedButton: document.getElementById("seedButton"),
  clearButton: document.getElementById("clearButton"),
  exportButton: document.getElementById("exportButton"),
  importInput: document.getElementById("importInput"),
  leftChoice: document.getElementById("leftChoice"),
  rightChoice: document.getElementById("rightChoice"),
  skipButton: document.getElementById("skipButton"),
  battleHelper: document.getElementById("battleHelper"),
  leaderboard: document.getElementById("leaderboard"),
  songCount: document.getElementById("songCount"),
  matchCount: document.getElementById("matchCount"),
  topRating: document.getElementById("topRating"),
  leaderboardItemTemplate: document.getElementById("leaderboardItemTemplate")
};

let state = loadState();

function loadState() {
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY));
    if (saved && Array.isArray(saved.songs)) {
      return {
        songs: saved.songs.map(normalizeSong),
        matchCount: Number(saved.matchCount) || 0
      };
    }
  } catch (error) {
    console.warn("Could not load saved songs", error);
  }

  return {
    songs: [],
    matchCount: 0
  };
}

function normalizeSong(song) {
  return {
    id: song.id || crypto.randomUUID(),
    title: song.title || "Untitled track",
    artist: song.artist || "Unknown artist",
    album: song.album || "",
    rating: Number(song.rating) || BASE_RATING,
    wins: Number(song.wins) || 0,
    losses: Number(song.losses) || 0
  };
}

function saveState() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
}

function addSong(songLike) {
  const cleanTitle = songLike.title.trim();
  const cleanArtist = songLike.artist.trim();
  const cleanAlbum = songLike.album.trim();

  if (!cleanTitle || !cleanArtist) {
    return false;
  }

  state.songs.push({
    id: crypto.randomUUID(),
    title: cleanTitle,
    artist: cleanArtist,
    album: cleanAlbum,
    rating: BASE_RATING,
    wins: 0,
    losses: 0
  });

  saveState();
  render();
  return true;
}

function clearState() {
  state = {
    songs: [],
    matchCount: 0
  };

  saveState();
  render();
}

function seedSongs() {
  const existingKeys = new Set(state.songs.map(song => `${song.title}::${song.artist}`.toLowerCase()));

  sampleSongs.forEach(song => {
    const key = `${song.title}::${song.artist}`.toLowerCase();
    if (!existingKeys.has(key)) {
      state.songs.push({
        id: crypto.randomUUID(),
        title: song.title,
        artist: song.artist,
        album: song.album,
        rating: BASE_RATING,
        wins: 0,
        losses: 0
      });
    }
  });

  saveState();
  render();
}

function exportSongs() {
  const blob = new Blob([JSON.stringify(state, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = "song-ladder-export.json";
  link.click();
  URL.revokeObjectURL(url);
}

function importSongs(file) {
  if (!file) {
    return;
  }

  const reader = new FileReader();
  reader.onload = event => {
    try {
      const parsed = JSON.parse(String(event.target?.result || ""));
      if (!parsed || !Array.isArray(parsed.songs)) {
        throw new Error("Import file is missing a songs array.");
      }

      state = {
        songs: parsed.songs.map(normalizeSong),
        matchCount: Number(parsed.matchCount) || 0
      };

      saveState();
      render();
    } catch (error) {
      alert(`Could not import file: ${error.message}`);
    } finally {
      elements.importInput.value = "";
    }
  };

  reader.readAsText(file);
}

function getRankedSongs() {
  return [...state.songs].sort((a, b) => {
    if (b.rating !== a.rating) {
      return b.rating - a.rating;
    }
    return a.title.localeCompare(b.title);
  });
}

function pickMatchup() {
  if (state.songs.length < 2) {
    return null;
  }

  const ranked = getRankedSongs();
  const firstIndex = Math.floor(Math.random() * ranked.length);
  const offset = ranked.length > 2 ? 1 + Math.floor(Math.random() * Math.min(3, ranked.length - 1)) : 1;
  const secondIndex = (firstIndex + offset) % ranked.length;
  return [ranked[firstIndex], ranked[secondIndex]];
}

function expectedScore(player, opponent) {
  return 1 / (1 + 10 ** ((opponent.rating - player.rating) / 400));
}

function recordBattle(winnerId, loserId) {
  const winner = state.songs.find(song => song.id === winnerId);
  const loser = state.songs.find(song => song.id === loserId);

  if (!winner || !loser) {
    return;
  }

  const winnerExpected = expectedScore(winner, loser);
  const loserExpected = expectedScore(loser, winner);

  winner.rating = Math.round(winner.rating + K_FACTOR * (1 - winnerExpected));
  loser.rating = Math.round(loser.rating + K_FACTOR * (0 - loserExpected));
  winner.wins += 1;
  loser.losses += 1;
  state.matchCount += 1;

  saveState();
  render();
}

function removeSong(songId) {
  state.songs = state.songs.filter(song => song.id !== songId);
  saveState();
  render();
}

function renderChoice(button, song, toneLabel) {
  if (!song) {
    button.disabled = true;
    button.innerHTML = "";
    return;
  }

  button.disabled = false;
  button.dataset.songId = song.id;
  button.innerHTML = `
    <span class="choice-label">${toneLabel}</span>
    <strong class="choice-title">${escapeHtml(song.title)}</strong>
    <span class="choice-artist">${escapeHtml(song.artist)}</span>
    <span class="choice-meta">
      ${song.album ? `${escapeHtml(song.album)}<br>` : ""}Rating ${song.rating} | ${song.wins}W ${song.losses}L
    </span>
  `;
}

function renderLeaderboard() {
  elements.leaderboard.innerHTML = "";
  const rankedSongs = getRankedSongs();

  if (!rankedSongs.length) {
    const empty = document.createElement("li");
    empty.className = "empty-state";
    empty.textContent = "No songs yet. Add a few tracks or load the sample library.";
    elements.leaderboard.appendChild(empty);
    return;
  }

  rankedSongs.forEach((song, index) => {
    const fragment = elements.leaderboardItemTemplate.content.cloneNode(true);
    const item = fragment.querySelector(".leaderboard-item");
    const name = fragment.querySelector(".song-name");
    const meta = fragment.querySelector(".song-meta");
    const score = fragment.querySelector(".song-score");
    const removeButton = fragment.querySelector(".delete-button");

    name.textContent = `${index + 1}. ${song.title}`;
    meta.textContent = `${song.artist}${song.album ? ` | ${song.album}` : ""} | ${song.wins} wins / ${song.losses} losses`;
    score.textContent = song.rating;
    removeButton.dataset.songId = song.id;

    item.dataset.songId = song.id;
    elements.leaderboard.appendChild(fragment);
  });
}

function renderStats() {
  const rankedSongs = getRankedSongs();
  elements.songCount.textContent = String(state.songs.length);
  elements.matchCount.textContent = String(state.matchCount);
  elements.topRating.textContent = rankedSongs[0] ? String(rankedSongs[0].rating) : "0";
}

function renderBattle() {
  const matchup = pickMatchup();

  if (!matchup) {
    elements.battleHelper.textContent = "Add at least two songs to start ranking.";
    renderChoice(elements.leftChoice, null, "");
    renderChoice(elements.rightChoice, null, "");
    elements.skipButton.disabled = true;
    return;
  }

  const [leftSong, rightSong] = matchup;
  elements.battleHelper.textContent = "Choose the song you like more right now. The leaderboard updates instantly.";
  renderChoice(elements.leftChoice, leftSong, "Pick this one");
  renderChoice(elements.rightChoice, rightSong, "Or this one");
  elements.skipButton.disabled = false;
}

function render() {
  renderStats();
  renderBattle();
  renderLeaderboard();
}

function escapeHtml(value) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

elements.songForm.addEventListener("submit", event => {
  event.preventDefault();

  const success = addSong({
    title: elements.titleInput.value,
    artist: elements.artistInput.value,
    album: elements.albumInput.value
  });

  if (!success) {
    alert("Please enter both a song title and an artist.");
    return;
  }

  elements.songForm.reset();
  elements.titleInput.focus();
});

elements.seedButton.addEventListener("click", seedSongs);
elements.clearButton.addEventListener("click", () => {
  const shouldClear = window.confirm("Reset the app and remove all songs?");
  if (shouldClear) {
    clearState();
  }
});
elements.exportButton.addEventListener("click", exportSongs);
elements.importInput.addEventListener("change", event => {
  const [file] = event.target.files || [];
  importSongs(file);
});

elements.leftChoice.addEventListener("click", () => {
  recordBattle(elements.leftChoice.dataset.songId, elements.rightChoice.dataset.songId);
});

elements.rightChoice.addEventListener("click", () => {
  recordBattle(elements.rightChoice.dataset.songId, elements.leftChoice.dataset.songId);
});

elements.skipButton.addEventListener("click", renderBattle);

elements.leaderboard.addEventListener("click", event => {
  const target = event.target;
  if (!(target instanceof HTMLElement)) {
    return;
  }

  if (target.matches(".delete-button")) {
    removeSong(target.dataset.songId);
  }
});

render();
