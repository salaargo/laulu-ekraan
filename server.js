const express = require('express');
const http = require('http');
const os = require('os');
const { Server } = require('socket.io');
const path = require('path');

const app = express();
app.set('trust proxy', 1);
const server = http.createServer(app);
const io = new Server(server, {
  cors: { origin: true },
});

const rooms = new Map();

function generateRoomId() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let id = '';
  for (let i = 0; i < 6; i++) {
    id += chars[Math.floor(Math.random() * chars.length)];
  }
  return id;
}

function getRoom(roomId) {
  if (!rooms.has(roomId)) {
    rooms.set(roomId, {
      adminId: null,
      viewers: new Set(),
      lastFrame: null,
      active: false,
    });
  }
  return rooms.get(roomId);
}

function deleteRoom(roomId) {
  rooms.delete(roomId);
}

app.use(express.static(path.join(__dirname, 'public')));

app.get('/', (_req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

app.get('/admin', (_req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'admin.html'));
});

app.get('/view/:roomId', (_req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'view.html'));
});

app.get('/api/host-info', (req, res) => {
  const port = process.env.PORT || 3000;
  const localIps = [];

  for (const interfaces of Object.values(os.networkInterfaces())) {
    for (const iface of interfaces) {
      if (iface.family === 'IPv4' && !iface.internal) {
        localIps.push(iface.address);
      }
    }
  }

  res.json({
    current: `${req.protocol}://${req.get('host')}`,
    local: localIps.map((ip) => `http://${ip}:${port}`),
  });
});

io.on('connection', (socket) => {
  socket.on('create-room', (callback) => {
    let roomId;
    do {
      roomId = generateRoomId();
    } while (rooms.has(roomId));

    const room = getRoom(roomId);
    room.adminId = socket.id;
    room.active = true;
    socket.join(roomId);
    socket.data.roomId = roomId;
    socket.data.role = 'admin';

    callback({ roomId });
  });

  socket.on('join-room', ({ roomId }, callback) => {
    const room = rooms.get(roomId);

    if (!room || !room.active) {
      callback({ success: false, error: 'Tuba ei ole aktiivne või ei eksisteeri.' });
      return;
    }

    socket.join(roomId);
    socket.data.roomId = roomId;
    socket.data.role = 'viewer';
    room.viewers.add(socket.id);

    if (room.lastFrame) {
      socket.emit('frame', room.lastFrame);
    }

    io.to(room.adminId).emit('viewer-count', room.viewers.size);
    callback({ success: true });
  });

  socket.on('frame', ({ roomId, image }) => {
    const room = rooms.get(roomId);
    if (!room || room.adminId !== socket.id) return;

    room.lastFrame = image;
    socket.to(roomId).emit('frame', image);
  });

  socket.on('stop-sharing', ({ roomId }) => {
    const room = rooms.get(roomId);
    if (!room || room.adminId !== socket.id) return;

    room.active = false;
    room.lastFrame = null;
    io.to(roomId).emit('sharing-stopped');
    deleteRoom(roomId);
  });

  socket.on('disconnect', () => {
    const { roomId, role } = socket.data;
    if (!roomId) return;

    const room = rooms.get(roomId);
    if (!room) return;

    if (role === 'admin') {
      room.active = false;
      io.to(roomId).emit('sharing-stopped');
      deleteRoom(roomId);
      return;
    }

    if (role === 'viewer') {
      room.viewers.delete(socket.id);
      if (room.adminId) {
        io.to(room.adminId).emit('viewer-count', room.viewers.size);
      }
    }
  });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', () => {
  console.log(`Server töötab pordil ${PORT}`);
});
