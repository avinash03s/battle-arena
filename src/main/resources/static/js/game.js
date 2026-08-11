      for (let wy = y + 30; wy < y + h - 20; wy += 45) {
        ctx.fillRect(wx, wy, winSize, winSize);
      }
    }
    ctx.strokeStyle = '#3d3226';
    ctx.lineWidth = 2;
    ctx.strokeRect(x, y, w, h);
  } else if (type === 'crate') {
    ctx.fillStyle = '#c98a3f';
    ctx.fillRect(x, y, w, h);
    ctx.strokeStyle = '#7a5220';
    ctx.lineWidth = 3;
    ctx.strokeRect(x, y, w, h);
    ctx.beginPath();
    ctx.moveTo(x, y); ctx.lineTo(x + w, y + h);
    ctx.moveTo(x + w, y); ctx.lineTo(x, y + h);
    ctx.stroke();
  } else if (type === 'border') {
    ctx.fillStyle = '#3a4150';
    ctx.fillRect(x, y, w, h);
  }
}

function drawMinimap() {
  const isMobile = window.matchMedia('(hover: none) and (pointer: coarse)').matches;
  const mmSize = isMobile ? 100 : 120;
  const mmX = 16;
  const mmY = isMobile ? 55 : 70;
  const scale = mmSize / mapInfo.width;
  const mmHeight = mapInfo.height * scale;

  ctx.fillStyle = 'rgba(43, 32, 22, 0.85)';
  ctx.fillRect(mmX, mmY, mmSize, mmHeight);
  ctx.strokeStyle = 'rgba(240, 223, 192, 0.4)';
  ctx.lineWidth = 2;
  ctx.strokeRect(mmX, mmY, mmSize, mmHeight);

  mapInfo.walls.forEach(w => {
    if (w.type !== 'building' && w.type !== 'crate' && w.type !== 'border') return;
    const wx = mmX + w.x * scale;
    const wy = mmY + w.y * scale;
    const ww = w.w * scale;
    const wh = w.h * scale;

    if (w.type === 'building') {
      ctx.fillStyle = '#8a7862';
    } else if (w.type === 'crate') {
      ctx.fillStyle = '#c98a3f';
    } else {
      ctx.fillStyle = '#3a4150';
    }
    ctx.fillRect(wx, wy, Math.max(ww, 1), Math.max(wh, 1));
  });

  latestState.players.forEach(p => {
    if (!p.alive) return;
    const px = mmX + p.x * scale;
    const py = mmY + p.y * scale;
    ctx.fillStyle = p.id === selfId ? '#3aa0ff' : '#c9722f';
    ctx.beginPath();
    ctx.arc(px, py, p.id === selfId ? 4 : 3, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = '#fff';
    ctx.lineWidth = 1;
    ctx.stroke();
  });

  ctx.strokeStyle = 'rgba(255,255,255,0.5)';
  ctx.lineWidth = 1;
  ctx.strokeRect(
    mmX + camX * scale,
    mmY + camY * scale,
    canvas.width * scale,
    canvas.height * scale
  );
}