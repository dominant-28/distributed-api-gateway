const express = require('express');
const app = express();
const PORT = 7000;

app.get('/api/products/:id',(req,res)=>{
    res.json({
        productID : req.params.id,
        name: "Sample Product",
        serveredBy: "backend-a"
        
    });
});

app.listen(PORT,()=>{
    console.log(`backend-a is listening on port ${PORT}`);
});